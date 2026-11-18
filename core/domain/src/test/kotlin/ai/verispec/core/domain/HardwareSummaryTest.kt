package ai.verispec.core.domain

import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.HardwareSummary
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.TelemetryInventory
import ai.verispec.core.domain.model.ThermalStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class HardwareSummaryTest : StringSpec({

    fun iteration(i: Int, clocks: ClockReading?) = IterationRecord(
        index = i, elapsedMillis = i * 1_000L, timeToFirstTokenMicros = 1_000, totalMicros = 1_000,
        prefillTokens = 1, decodeTokens = 1, decodeTokensPerSecond = 10.0, prefillTokensPerSecond = 1.0,
        thermalStatusAtStart = ThermalStatus.NONE, thermalStatusAtEnd = ThermalStatus.NONE, wasThrottled = false,
        clocks = clocks,
    )

    fun observations(iterations: List<IterationRecord>, power: List<PowerSample>, inventory: TelemetryInventory? = null) = Observations(
        iterations = iterations,
        thermalTimeline = emptyList(),
        powerTimeline = power,
        memoryPeaks = MemoryEnvelope(1, 1, null, 1, 1),
        outputDigests = emptyList(),
        runStartedAtEpochMillis = 0,
        runDurationMillis = 1_000,
        telemetry = inventory,
    )

    "a bundle from before 1.2.0 has no summary" {
        HardwareSummary.of(observations(listOf(iteration(0, null)), listOf(PowerSample(0, null, null, 80, false)))).shouldBeNull()
    }

    "ranges come from the fastest core, the GPU, the battery and the screen samples" {
        val its = listOf(
            iteration(0, ClockReading(listOf(1_098_000, 2_800_000), 848_000)),
            iteration(1, ClockReading(null, 251_000)), // a core unreadable: no CPU entry, GPU still counts
            iteration(2, ClockReading(listOf(900_000, 1_800_000), null)),
        )
        val power = listOf(
            PowerSample(0, null, null, 80, false, batteryTemperatureDeciC = 291, screenOn = true),
            PowerSample(1_000, null, null, 80, false, batteryTemperatureDeciC = 304, screenOn = false),
        )
        val inventory = TelemetryInventory(listOf("cpu clock (2 cores)"), listOf("thermal zones: /sys/class/thermal denied to apps"))
        val s = HardwareSummary.of(observations(its, power, inventory))!!
        s.fastestCoreKhz shouldBe 1_800_000L..2_800_000L
        s.gpuKhz shouldBe 251_000L..848_000L
        s.batteryTemperatureDeciC shouldBe 291..304
        s.screenOnFraction shouldBe 0.5
        val rows = s.rows()
        rows shouldContain ("battery temperature" to "29.1–30.4 °C")
        rows shouldContain ("fastest core clock" to "1800–2800 MHz")
        rows shouldContain ("GPU clock" to "251–848 MHz")
        rows shouldContain ("screen on" to "50 % of samples")
        rows shouldContain ("unavailable" to "thermal zones: /sys/class/thermal denied to apps")
    }

    "sub-zero battery temperatures keep their sign" {
        val power = listOf(PowerSample(0, null, null, 80, false, batteryTemperatureDeciC = -5))
        HardwareSummary.of(observations(emptyList(), power))!!.rows() shouldContain ("battery temperature" to "-0.5–-0.5 °C")
    }
})
