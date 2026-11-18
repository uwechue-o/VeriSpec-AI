package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.math.Numerics
import ai.verispec.core.domain.math.SustainedLoad
import ai.verispec.core.domain.model.EnvelopeValidity
import ai.verispec.core.domain.model.EvidenceFacts
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.ThermalEnvelope
import ai.verispec.core.domain.model.ThermalStatus

/** Peak, settled steady state, decay and throttling. Pure arithmetic over observations. */
class ComputeThermalEnvelopeUseCase {

    operator fun invoke(observations: Observations): ThermalEnvelope? {
        val iterations = observations.iterations
        if (iterations.isEmpty()) return null

        val tps = iterations.map { it.decodeTokensPerSecond }
        val peak = SustainedLoad.windowedPeak(tps)
        val steady = SustainedLoad.steadyState(tps)
        val decay = steady?.let { if (peak > 0.0) ((peak - it.value) / peak * 100.0).coerceAtLeast(0.0) else 0.0 }

        val onset = { start: ThermalStatus, end: ThermalStatus -> start.isThrottleOnset || end.isThrottleOnset }
        val firstThrottle = iterations.firstOrNull { onset(it.thermalStatusAtStart, it.thermalStatusAtEnd) }?.elapsedMillis
            ?: observations.thermalTimeline.firstOrNull { it.status.isThrottleOnset }?.elapsedMillis

        val maxStatus = (iterations.flatMap { listOf(it.thermalStatusAtStart, it.thermalStatusAtEnd) } +
            observations.thermalTimeline.map { it.status })
            .fold(ThermalStatus.UNKNOWN) { acc, s -> ThermalStatus.maxOf(acc, s) }

        val ttft = iterations.map { it.timeToFirstTokenMicros }
        return ThermalEnvelope(
            iterationsAnalyzed = iterations.size,
            peakDecodeTokensPerSecond = peak,
            steadyStateDecodeTokensPerSecond = steady?.value,
            steadyStateFromIteration = steady?.startIndex,
            decayPercent = decay,
            timeToFirstThrottleMillis = firstThrottle,
            throttledIterations = iterations.count { onset(it.thermalStatusAtStart, it.thermalStatusAtEnd) },
            materiallyThrottledIterations = iterations.count { it.wasThrottled },
            maxThermalStatus = maxStatus,
            minHeadroom = observations.thermalTimeline.mapNotNull { it.headroom }.minOrNull(),
            meanTimeToFirstTokenMicros = Numerics.mean(ttft.map { it.toDouble() }),
            p95TimeToFirstTokenMicros = Numerics.percentile(ttft, 95.0),
            validity = validity(observations),
        )
    }

    companion object {
        /** One verdict rule for the device and the host. */
        fun validity(observations: Observations): EnvelopeValidity {
            val gate = observations.preconditions
            return when {
                observations.iterations.size < EvidenceFacts.MIN_ITERATIONS_FOR_ENVELOPE -> EnvelopeValidity.INSUFFICIENT_DATA
                gate == null -> EnvelopeValidity.NO_PRECONDITION_GATE
                !gate.satisfied -> EnvelopeValidity.PRECONDITIONS_UNMET
                else -> EnvelopeValidity.VALID
            }
        }
    }
}
