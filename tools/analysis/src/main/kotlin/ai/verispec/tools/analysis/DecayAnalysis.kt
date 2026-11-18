package ai.verispec.tools.analysis

import ai.verispec.core.domain.math.BatteryCurrent
import ai.verispec.core.domain.math.SustainedLoad
import ai.verispec.core.domain.model.EnvelopeValidity
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.usecase.ComputeThermalEnvelopeUseCase
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math3.stat.regression.SimpleRegression

/**
 * Sustained-load decay analysis: the ThermalBench module, with the evidence
 * spine already under it. Peak, steady state and validity come from
 * [SustainedLoad] and [ComputeThermalEnvelopeUseCase], the same code the device
 * runs, so the report and the phone never disagree. Regression is over
 * iteration index, not wall-clock, so a paused run does not distort the slope.
 */
data class DecayAnalysis(
    val bundleId: String,
    val deviceLabel: String,
    val iterations: Int,
    /** Best rolling-median window, not the single fastest iteration. */
    val peakTokensPerSecond: Double,
    /** Median of the settled tail; null when the run never settled. */
    val steadyStateTokensPerSecond: Double?,
    val steadyStateFromIteration: Int?,
    /** Null without a steady state. */
    val decayPercent: Double?,
    /** Least-squares slope of tokens/s against iteration index. Negative means degradation. */
    val slopePerIteration: Double,
    val rSquared: Double,
    /** First iteration at LIGHT or worse. */
    val timeToFirstThrottleMillis: Long?,
    /** Share of iterations at LIGHT or worse. */
    val throttledFraction: Double,
    /** Share of iterations at MODERATE or worse. */
    val materiallyThrottledFraction: Double,
    val maxThermalStatus: ThermalStatus,
    val minHeadroom: Float?,
    val p50TokensPerSecond: Double,
    val p95TimeToFirstTokenMicros: Double,
    val p99TimeToFirstTokenMicros: Double,
    val coefficientOfVariation: Double,
    /** Mean battery power while discharging; null when the device reported no current. */
    val meanPowerMilliWatts: Double?,
    val energyPerTokenMilliJoules: Double?,
    /** The unit the device's raw battery current was read in (see BatteryCurrent); null without current samples. */
    val batteryCurrentUnit: BatteryCurrent.Unit?,
    val validity: EnvelopeValidity,
)

class ComputeDecayUseCase {

    operator fun invoke(bundle: EvidenceBundle): DecayAnalysis? {
        val iterations = bundle.observations.iterations
        if (iterations.isEmpty()) return null

        val tps = DescriptiveStatistics()
        val ttft = DescriptiveStatistics()
        val regression = SimpleRegression()
        iterations.forEach {
            tps.addValue(it.decodeTokensPerSecond)
            ttft.addValue(it.timeToFirstTokenMicros.toDouble())
            regression.addData(it.index.toDouble(), it.decodeTokensPerSecond)
        }

        val series = iterations.map { it.decodeTokensPerSecond }
        val peak = SustainedLoad.windowedPeak(series)
        val steady = SustainedLoad.steadyState(series)
        val decay = steady?.let { if (peak > 0) ((peak - it.value) / peak * 100.0).coerceAtLeast(0.0) else 0.0 }
        val onset = iterations.filter { it.thermalStatusAtStart.isThrottleOnset || it.thermalStatusAtEnd.isThrottleOnset }

        val meanPower = BatteryCurrent.meanDischargePowerMilliWatts(bundle.observations.powerTimeline)
        val totalTokens = iterations.sumOf { it.decodeTokens }.toDouble()
        val energyPerToken = if (meanPower != null && totalTokens > 0) {
            meanPower * (bundle.observations.runDurationMillis / 1000.0) / totalTokens
        } else {
            null
        }

        return DecayAnalysis(
            bundleId = bundle.bundleId.value,
            deviceLabel = "${bundle.device.soc.model}/${bundle.provenance.delegateActuallyUsed}",
            iterations = iterations.size,
            peakTokensPerSecond = peak,
            steadyStateTokensPerSecond = steady?.value,
            steadyStateFromIteration = steady?.startIndex,
            decayPercent = decay,
            slopePerIteration = if (iterations.size >= 2) regression.slope else 0.0,
            rSquared = if (iterations.size >= 2) regression.rSquare.takeUnless { it.isNaN() } ?: 0.0 else 0.0,
            timeToFirstThrottleMillis = onset.firstOrNull()?.elapsedMillis,
            throttledFraction = onset.size.toDouble() / iterations.size,
            materiallyThrottledFraction = iterations.count { it.wasThrottled }.toDouble() / iterations.size,
            maxThermalStatus = iterations
                .flatMap { listOf(it.thermalStatusAtStart, it.thermalStatusAtEnd) }
                .fold(ThermalStatus.UNKNOWN) { a, b -> ThermalStatus.maxOf(a, b) },
            minHeadroom = bundle.observations.thermalTimeline.mapNotNull { it.headroom }.minOrNull(),
            p50TokensPerSecond = tps.getPercentile(50.0),
            p95TimeToFirstTokenMicros = ttft.getPercentile(95.0),
            p99TimeToFirstTokenMicros = ttft.getPercentile(99.0),
            coefficientOfVariation = if (tps.mean != 0.0) tps.standardDeviation / tps.mean else 0.0,
            meanPowerMilliWatts = meanPower,
            energyPerTokenMilliJoules = energyPerToken,
            batteryCurrentUnit = BatteryCurrent.inferUnit(bundle.observations.powerTimeline),
            validity = ComputeThermalEnvelopeUseCase.validity(bundle.observations),
        )
    }
}

/** Fleet-level aggregation across devices for one manifest. */
data class FleetDecaySummary(
    val manifestId: String,
    val perDevice: List<DecayAnalysis>,
) {
    /** Only devices whose runs settled have a decay or a steady state to compare. */
    val worstDecayPercent: Double get() = perDevice.mapNotNull { it.decayPercent }.maxOrNull() ?: 0.0
    val bestSteadyState: DecayAnalysis? get() = perDevice.filter { it.steadyStateTokensPerSecond != null }.maxByOrNull { it.steadyStateTokensPerSecond!! }
    val worstSteadyState: DecayAnalysis? get() = perDevice.filter { it.steadyStateTokensPerSecond != null }.minByOrNull { it.steadyStateTokensPerSecond!! }

    /** How far apart the fastest and slowest devices are at steady state, in percent. */
    val steadyStateSpreadPercent: Double
        get() {
            val best = bestSteadyState?.steadyStateTokensPerSecond ?: return 0.0
            val worst = worstSteadyState?.steadyStateTokensPerSecond ?: return 0.0
            return if (best > 0) (best - worst) / best * 100.0 else 0.0
        }
}
