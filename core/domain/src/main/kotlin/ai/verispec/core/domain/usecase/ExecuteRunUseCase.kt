package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.math.WorkloadInputs
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.MemorySnapshot
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.RunFailure
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RunProgress
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.HardwareTelemetrySource
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.core.domain.port.MemoryTelemetrySource
import ai.verispec.core.domain.port.PowerTelemetrySource
import ai.verispec.core.domain.port.ThermalTelemetrySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Drives N iterations, emits progress, collects observations.
 *
 * Measurement is identical for every backend: the same loop, the same clock,
 * the same telemetry. No backend-specific tuning lives here — that is the
 * neutrality guardrail from GUIDE.md §8, made structural.
 */
class ExecuteRunUseCase(
    private val backends: Map<RuntimeId, InferenceBackend>,
    private val thermal: ThermalTelemetrySource,
    private val power: PowerTelemetrySource,
    private val memory: MemoryTelemetrySource,
    private val clock: Clock,
    /** Null where no clock or sensor reads are wired, e.g. in pure tests. */
    private val hardware: HardwareTelemetrySource? = null,
) {
    operator fun invoke(manifest: RunManifest): Flow<RunProgress> = channelFlow {
        val backend = backends[manifest.backend.runtime]
        if (backend == null) {
            send(RunProgress.Failed(RunFailure.BackendUnavailable(manifest.backend.runtime, "no adapter compiled in")))
            return@channelFlow
        }

        val caps = backend.capabilities()
        when {
            !caps.available ->
                send(RunProgress.Failed(RunFailure.BackendUnavailable(caps.runtime, caps.unavailableReason)))
            manifest.model.format !in caps.supportedFormats ->
                send(RunProgress.Failed(RunFailure.UnsupportedFormat(caps.runtime, manifest.model.format)))
            manifest.backend.delegate !in caps.supportedDelegates ->
                send(RunProgress.Failed(RunFailure.DelegateUnavailable(caps.runtime, manifest.backend.delegate, caps.supportedDelegates)))
            else -> {
                send(RunProgress.Started(manifest.schedule.iterations, manifest.schedule.warmupIterations, caps))
                runMeasured(backend, manifest)
            }
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<RunProgress>.runMeasured(
        backend: InferenceBackend,
        manifest: RunManifest,
    ) {
        val startNanos = clock.monotonicNanos()
        val startEpoch = clock.epochMillis()
        fun elapsedMillis(): Long = (clock.monotonicNanos() - startNanos) / 1_000_000

        val thermalSamples = ArrayList<ThermalSample>()
        val powerSamples = ArrayList<PowerSample>()
        val memorySnapshots = ArrayList<MemorySnapshot>()
        var liveThermal = ThermalStatus.UNKNOWN
        var liveHeadroom: Float? = null

        val thermalJob: Job = launch {
            thermal.stream().collect { sample ->
                val stamped = sample.copy(elapsedMillis = elapsedMillis())
                synchronized(thermalSamples) { thermalSamples += stamped }
                liveThermal = stamped.status
                liveHeadroom = stamped.headroom
            }
        }
        val powerJob: Job = launch {
            power.stream().collect { sample ->
                synchronized(powerSamples) { powerSamples += sample.copy(elapsedMillis = elapsedMillis()) }
            }
        }

        var loaded: LoadedModel? = null
        try {
            memorySnapshots += memory.snapshot()
            val inventory = hardware?.inventory()

            val loadStart = clock.monotonicNanos()
            loaded = try {
                backend.load(manifest.model, manifest.backend.delegate, manifest.backend.threadCount, manifest.backend.accelerator)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                send(RunProgress.Failed(RunFailure.ModelLoadFailed(e.message ?: e::class.simpleName ?: "unknown")))
                return
            }
            send(
                RunProgress.ModelLoaded(
                    effectiveDelegate = loaded.effectiveDelegate,
                    runtimeVersionObserved = loaded.runtimeVersionObserved,
                    loadMillis = (clock.monotonicNanos() - loadStart) / 1_000_000,
                ),
            )

            for (w in 0 until manifest.schedule.warmupIterations) {
                try {
                    backend.infer(loaded, WorkloadInputs.forIteration(manifest, w))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val detail = e.message ?: e::class.simpleName ?: "unknown"
                    send(RunProgress.Failed(RunFailure.InferenceFailed(w, "during warm-up ${w + 1} of ${manifest.schedule.warmupIterations}: $detail")))
                    return
                }
                send(RunProgress.Warmup(w + 1, manifest.schedule.warmupIterations))
            }

            val records = ArrayList<IterationRecord>(manifest.schedule.iterations)
            val digests = ArrayList<OutputDigest>(manifest.schedule.iterations)
            var stoppedEarly: String? = null

            for (i in 0 until manifest.schedule.iterations) {
                val cap = manifest.schedule.sustainedDurationMillis
                if (cap != null && elapsedMillis() >= cap) {
                    stoppedEarly = "sustainedDurationMillis=$cap reached after $i iterations"
                    break
                }

                val statusAtStart = liveThermal
                val iterationElapsed = elapsedMillis()
                val result = try {
                    backend.infer(loaded, WorkloadInputs.forIteration(manifest, i))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    send(RunProgress.Failed(RunFailure.InferenceFailed(i, e.message ?: e::class.simpleName ?: "unknown")))
                    return
                }
                val statusAtEnd = liveThermal
                // After the timed region, for every backend alike, so it cannot bias any measurement.
                val clocks = hardware?.clocks()

                val record = IterationRecord(
                    index = i,
                    elapsedMillis = iterationElapsed,
                    timeToFirstTokenMicros = result.timeToFirstTokenMicros,
                    totalMicros = result.totalMicros,
                    prefillTokens = result.prefillTokens,
                    decodeTokens = result.decodeTokens,
                    decodeTokensPerSecond = result.decodeTokensPerSecond,
                    prefillTokensPerSecond = result.prefillTokensPerSecond,
                    thermalStatusAtStart = statusAtStart,
                    thermalStatusAtEnd = statusAtEnd,
                    wasThrottled = statusAtStart.isThrottling || statusAtEnd.isThrottling,
                    clocks = clocks,
                )
                val digest = OutputDigest(i, result.outputSha256, result.logitProjection)
                records += record
                digests += digest
                if (i % MEMORY_SAMPLE_EVERY == 0) memorySnapshots += memory.snapshot()

                send(
                    RunProgress.Iteration(
                        record = record,
                        digest = digest,
                        liveThermal = liveThermal,
                        liveHeadroom = liveHeadroom,
                        completed = i + 1,
                        total = manifest.schedule.iterations,
                    ),
                )

                if (manifest.schedule.interIterationDelayMillis > 0) delay(manifest.schedule.interIterationDelayMillis)
            }

            memorySnapshots += memory.snapshot()
            thermalJob.cancel()
            powerJob.cancel()

            val observations = Observations(
                iterations = records,
                thermalTimeline = synchronized(thermalSamples) { thermalSamples.toList() },
                powerTimeline = synchronized(powerSamples) { powerSamples.toList() },
                memoryPeaks = MemoryEnvelope.from(memorySnapshots),
                outputDigests = digests,
                stoppedEarlyReason = stoppedEarly,
                runStartedAtEpochMillis = startEpoch,
                runDurationMillis = elapsedMillis(),
                telemetry = inventory,
            )
            send(RunProgress.Completed(observations, loaded))
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            thermalJob.cancel()
            powerJob.cancel()
            loaded?.let { runCatching { backend.unload(it) } }
        }
    }

    private companion object {
        const val MEMORY_SAMPLE_EVERY = 5
    }
}
