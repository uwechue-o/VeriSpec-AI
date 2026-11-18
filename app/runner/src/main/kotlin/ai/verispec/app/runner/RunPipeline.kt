package ai.verispec.app.runner

import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.PreconditionOutcome
import ai.verispec.core.domain.model.PreconditionPolicy
import ai.verispec.core.domain.model.PreconditionState
import ai.verispec.core.domain.model.RunProgress
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.domain.usecase.AssembleEvidenceUseCase
import ai.verispec.core.domain.usecase.AwaitPreconditionsUseCase
import ai.verispec.core.domain.usecase.ExecuteRunUseCase
import ai.verispec.core.domain.usecase.MapClausesUseCase
import ai.verispec.core.domain.usecase.ProfileDeviceUseCase
import ai.verispec.core.domain.usecase.SignEvidenceUseCase
import ai.verispec.core.domain.usecase.ValidateManifestUseCase
import ai.verispec.data.persistence.RunRepository
import ai.verispec.data.runtime.api.BackendRegistry
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole evidence spine for one run, in order:
 * validate → precondition gate → profile device → execute (flushing every
 * iteration) → map clauses → assemble → sign → store. Holds only a ManifestId; everything else is
 * re-resolved from persistence so a restarted worker is idempotent.
 */
@Singleton
class RunPipeline @Inject constructor(
    private val runs: RunRepository,
    private val evidence: EvidenceRepository,
    private val registry: BackendRegistry,
    private val validate: ValidateManifestUseCase,
    private val awaitPreconditions: AwaitPreconditionsUseCase,
    private val profileDevice: ProfileDeviceUseCase,
    private val execute: ExecuteRunUseCase,
    private val mapClauses: MapClausesUseCase,
    private val assemble: AssembleEvidenceUseCase,
    private val sign: SignEvidenceUseCase,
) {
    sealed interface Outcome {
        data class Completed(val bundleId: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun run(id: ManifestId, onProgress: suspend (RunProgress) -> Unit): Outcome {
        val record = runs.get(id) ?: return fail(id, "No run stored for manifest ${id.short}")
        val manifest = record.manifest

        val discovery = registry.discover()
        val validation = validate(manifest, discovery.capabilities[manifest.backend.runtime])
        if (!validation.isValid) {
            return fail(id, "Manifest rejected: " + validation.errors.joinToString("; ") { "${it.field}: ${it.message}" })
        }

        runs.markRunning(id)

        // Before anything that heats the device: profiling and model loading come after the gate.
        val gate: PreconditionOutcome? = try {
            manifest.preconditions?.let { policy ->
                awaitGate(policy, onProgress).also { outcome ->
                    if (!outcome.satisfied && !policy.proceedIfUnmet) {
                        return fail(id, "Preconditions not met after ${outcome.waitedMillis / 1000} s: " + outcome.unmetAtStart.joinToString("; "))
                    }
                }
            }
        } catch (ce: CancellationException) {
            runs.markCancelled(id)
            throw ce
        }

        val device = profileDevice()

        var observations: Observations? = null
        var loaded: LoadedModel? = null
        var failure: String? = null

        try {
            execute(manifest).collect { progress ->
                when (progress) {
                    is RunProgress.Iteration -> runs.appendIteration(id, progress.record, progress.digest)
                    is RunProgress.Completed -> {
                        observations = progress.observations.copy(preconditions = gate)
                        loaded = progress.loaded
                    }
                    is RunProgress.Failed -> failure = progress.failure.message
                    else -> Unit
                }
                onProgress(progress)
            }
        } catch (ce: CancellationException) {
            runs.markCancelled(id)
            throw ce
        }

        val obs = observations
        val model = loaded
        if (failure != null || obs == null || model == null) {
            return fail(id, failure ?: "Run ended without observations")
        }

        val provenanceNotes = discovery.gapNotes()
        val facts = EvidenceFacts.derive(
            manifest = manifest,
            device = device,
            provenance = assemble.buildProvenance(manifest, device, model, provenanceNotes),
            observations = obs,
            signaturePlanned = true,
            hardwareAttestationPlanned = true,
            divergenceReportAttached = false,
            quantizationDeltaAttached = false,
        )
        val coverage = mapClauses(facts)
        val assembled = assemble(manifest, device, model, obs, coverage, provenanceNotes)
        val bundle = sign(assembled)
        val bundleId = evidence.save(bundle)
        runs.markCompleted(id, bundleId.value)
        return Outcome.Completed(bundleId.value)
    }

    /** Waits at the gate, reporting every poll, and turns its terminal state into the recorded outcome. */
    private suspend fun awaitGate(policy: PreconditionPolicy, onProgress: suspend (RunProgress) -> Unit): PreconditionOutcome {
        var outcome: PreconditionOutcome? = null
        awaitPreconditions(policy).collect { state ->
            when (state) {
                is PreconditionState.Waiting -> onProgress(RunProgress.AwaitingPreconditions(state))
                is PreconditionState.Satisfied ->
                    outcome = PreconditionOutcome(true, state.waitedMillis, emptyList(), state.at)
                is PreconditionState.TimedOut -> {
                    val unmet = state.unmet.map { it.description }.ifEmpty {
                        listOf("Conditions held for ${state.heldMillis / 1000} s of the ${policy.stabilizationWindowMillis / 1000} s stabilization window")
                    }
                    outcome = PreconditionOutcome(false, state.waitedMillis, unmet, state.latest)
                }
            }
        }
        return checkNotNull(outcome) { "Precondition gate ended without a terminal state" }
    }

    private suspend fun fail(id: ManifestId, reason: String): Outcome {
        runs.markFailed(id, reason)
        return Outcome.Failed(reason)
    }
}
