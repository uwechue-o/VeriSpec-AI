package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.ClauseCoverage
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.ProvenanceRecord
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.Hasher

/** Observations → canonical, unsigned bundle plus its content hash. */
class AssembleEvidenceUseCase(
    private val canonicalizer: Canonicalizer,
    private val hasher: Hasher,
    private val clock: Clock,
) {
    data class Assembled(val bundleId: BundleId, val unsigned: UnsignedBundle, val canonicalBytes: ByteArray)

    operator fun invoke(
        manifest: RunManifest,
        device: DeviceProfile,
        loaded: LoadedModel,
        observations: Observations,
        clauseCoverage: List<ClauseCoverage>,
        provenanceNotes: List<String> = emptyList(),
    ): Assembled {
        val provenance = buildProvenance(manifest, device, loaded, provenanceNotes)
        val unsigned = UnsignedBundle(
            schemaVersion = EvidenceBundle.CURRENT_SCHEMA_VERSION,
            manifest = manifest,
            device = device,
            provenance = provenance,
            observations = observations,
            clauseCoverage = clauseCoverage.sortedWith(compareBy({ it.framework.ordinal }, { it.clauseId.value })),
        )
        val bytes = canonicalizer.bundleBytes(unsigned)
        return Assembled(BundleId(hasher.sha256(bytes).hex), unsigned, bytes)
    }

    fun buildProvenance(
        manifest: RunManifest,
        device: DeviceProfile,
        loaded: LoadedModel,
        notes: List<String>,
    ): ProvenanceRecord {
        val fallbackNotes = if (loaded.effectiveDelegate != loaded.requestedDelegate) {
            listOf("Delegate fallback: requested ${loaded.requestedDelegate}, executed on ${loaded.effectiveDelegate}")
        } else {
            emptyList()
        }
        val requested = manifest.backend.accelerator
        val acceleratorNotes = if (requested != null && loaded.acceleratorUsed != requested) {
            listOf("Accelerator fallback: requested $requested, which the ${manifest.backend.delegate} delegate did not run on")
        } else {
            emptyList()
        }
        return ProvenanceRecord(
            modelSha256 = manifest.model.sha256,
            modelFormat = manifest.model.format,
            runtime = manifest.backend.runtime,
            runtimeVersionDeclared = manifest.backend.runtimeVersion,
            runtimeVersionObserved = loaded.runtimeVersionObserved,
            delegate = manifest.backend.delegate,
            delegateActuallyUsed = loaded.effectiveDelegate,
            npuDriverVersion = device.npuDriverVersion,
            gpuDriverVersion = device.gpuDriverVersion,
            toolVersion = manifest.toolVersion,
            capturedAtEpochMillis = clock.epochMillis(),
            notes = (fallbackNotes + acceleratorNotes + loaded.loadNotes + notes).sorted(),
            accelerator = requested,
            acceleratorActuallyUsed = loaded.acceleratorUsed,
            acceleratorsAvailable = loaded.acceleratorsAvailable,
        )
    }
}
