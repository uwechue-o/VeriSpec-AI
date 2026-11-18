package ai.verispec.app.ui.bundles

import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.ThermalStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelemetrySeriesTest {

    private fun iteration(i: Int, clocks: ClockReading?) = IterationRecord(
        index = i, elapsedMillis = i * 2_000L, timeToFirstTokenMicros = 1_000, totalMicros = 1_000,
        prefillTokens = 1, decodeTokens = 1, decodeTokensPerSecond = 10.0, prefillTokensPerSecond = 1.0,
        thermalStatusAtStart = ThermalStatus.NONE, thermalStatusAtEnd = ThermalStatus.NONE, wasThrottled = false,
        clocks = clocks,
    )

    private fun observations(
        iterations: List<IterationRecord>,
        power: List<PowerSample> = emptyList(),
        thermal: List<ThermalSample> = emptyList(),
    ) = Observations(
        iterations = iterations,
        thermalTimeline = thermal,
        powerTimeline = power,
        memoryPeaks = MemoryEnvelope(1, 1, null, 1, 1),
        outputDigests = emptyList(),
        runStartedAtEpochMillis = 0,
        runDurationMillis = 1_000,
    )

    @Test
    fun `fastest core is the highest core clock, in MHz against seconds`() {
        val its = listOf(
            iteration(0, ClockReading(listOf(1_000_000, 2_630_000), 251_000)),
            iteration(1, ClockReading(listOf(1_000_000, 1_803_000), 251_000)),
        )
        val clock = TelemetrySeries.of(observations(its)).first { it.title == "Fastest core clock" }
        assertThat(clock.points.map { it.x to it.y }).containsExactly(0f to 2630f, 2f to 1803f).inOrder()
        assertThat(clock.fromZero).isTrue()
    }

    @Test
    fun `battery temperature is in degrees and not drawn from zero`() {
        val power = listOf(
            PowerSample(0, null, null, 80, false, batteryTemperatureDeciC = 299),
            PowerSample(1_500, null, null, 80, false, batteryTemperatureDeciC = 308),
        )
        val temp = TelemetrySeries.of(observations(emptyList(), power)).single()
        assertThat(temp.title).isEqualTo("Battery temperature")
        assertThat(temp.points.map { it.y }).containsExactly(29.9f, 30.8f).inOrder()
        assertThat(temp.fromZero).isFalse()
    }

    @Test
    fun `unreadable or single-sample signals are left out`() {
        val its = listOf(iteration(0, null), iteration(1, ClockReading(null, 251_000)))
        val thermal = listOf(ThermalSample(0, ThermalStatus.NONE, null), ThermalSample(500, ThermalStatus.NONE, null))
        assertThat(TelemetrySeries.of(observations(its, thermal = thermal))).isEmpty()
    }

    @Test
    fun `throttling thermal samples are flagged`() {
        val thermal = listOf(ThermalSample(0, ThermalStatus.NONE, 0.6f), ThermalSample(500, ThermalStatus.MODERATE, 0.9f))
        val headroom = TelemetrySeries.of(observations(emptyList(), thermal = thermal)).single()
        assertThat(headroom.points.map { it.throttled }).containsExactly(false, true).inOrder()
    }
}
