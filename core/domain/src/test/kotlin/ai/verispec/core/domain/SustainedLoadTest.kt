package ai.verispec.core.domain

import ai.verispec.core.domain.math.SustainedLoad
import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.model.EnvelopeValidity
import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.MemoryEnvelope
import ai.verispec.core.domain.model.Observations
import ai.verispec.core.domain.model.PreconditionOutcome
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.usecase.ComputeThermalEnvelopeUseCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SustainedLoadTest : StringSpec({

    "one lucky iteration does not set the peak" {
        val series = List(20) { 10.0 }.toMutableList().apply { this[7] = 50.0 }
        SustainedLoad.windowedPeak(series) shouldBe 10.0
    }

    "the peak is the best sustained window" {
        val series = List(5) { 20.0 } + List(15) { 12.0 }
        SustainedLoad.windowedPeak(series) shouldBe 20.0
    }

    "a series that throttles and then holds settles at its floor" {
        val series = List(10) { 20.0 } + List(5) { 20.0 - it * 2.0 } + List(20) { 10.0 + (it % 2) * 0.1 }
        val steady = SustainedLoad.steadyState(series).shouldNotBeNull()
        steady.value shouldBe (10.05 plusOrMinus 0.06)
        (steady.startIndex >= 14) shouldBe true
    }

    "a series still falling at the end has no steady state" {
        SustainedLoad.steadyState(List(30) { 30.0 - it * 0.5 }).shouldBeNull()
    }

    "isolated stalls in a flat run do not stop it settling" {
        // The 2026-11-11 Pixel 6 shape: flat near 22 with one half-speed stall every ~50 iterations.
        val series = List(3_000) { i -> if (i % 50 == 17) 11.0 else 21.8 + (i % 3) * 0.1 }
        val steady = SustainedLoad.steadyState(series).shouldNotBeNull()
        steady.value shouldBe (21.9 plusOrMinus 0.1)
        steady.startIndex shouldBe 0
    }

    "two stalls in one window still pass, three do not" {
        val two = List(30) { 20.0 }.toMutableList().apply { this[22] = 10.0; this[27] = 10.0 }
        SustainedLoad.steadyState(two).shouldNotBeNull()
        val three = List(30) { 20.0 }.toMutableList().apply { this[22] = 10.0; this[25] = 10.0; this[28] = 10.0 }
        SustainedLoad.steadyState(three).shouldBeNull()
    }

    "a run alternating between two speeds has not settled" {
        SustainedLoad.steadyState(List(40) { if (it % 2 == 0) 20.0 else 14.0 }).shouldBeNull()
    }

    "a stall at the very end does not hide a decline before it" {
        SustainedLoad.steadyState(List(30) { 30.0 - it * 0.5 } + listOf(5.0)).shouldBeNull()
    }

    "drift compares the two halves and ignores one outlier" {
        SustainedLoad.drift(listOf(10.0, 10.0, 10.0, 1.0, 10.0, 10.0, 10.0, 10.0)) shouldBe 0.0
        SustainedLoad.drift(List(5) { 20.0 } + List(5) { 18.0 }) shouldBe (0.105 plusOrMinus 0.001)
    }

    "the window grows with long runs" {
        SustainedLoad.steadyWindow(300) shouldBe 10
        SustainedLoad.steadyWindow(3_000) shouldBe 100
    }

    "a series shorter than the window has no steady state" {
        SustainedLoad.steadyState(List(9) { 10.0 }).shouldBeNull()
    }

    fun observations(tps: List<Double>, gate: PreconditionOutcome?, statusAt: (Int) -> ThermalStatus = { ThermalStatus.NONE }) = Observations(
        iterations = tps.mapIndexed { i, v ->
            IterationRecord(
                index = i, elapsedMillis = i * 1_000L, timeToFirstTokenMicros = 1_000, totalMicros = 1_000,
                prefillTokens = 1, decodeTokens = 1, decodeTokensPerSecond = v, prefillTokensPerSecond = 1.0,
                thermalStatusAtStart = statusAt(i), thermalStatusAtEnd = statusAt(i), wasThrottled = statusAt(i).isThrottling,
            )
        },
        thermalTimeline = emptyList(),
        powerTimeline = emptyList(),
        memoryPeaks = MemoryEnvelope(1, 1, null, 1, 1),
        outputDigests = emptyList(),
        runStartedAtEpochMillis = 0,
        runDurationMillis = tps.size * 1_000L,
        preconditions = gate,
    )

    val conditions = DeviceConditions(ThermalStatus.NONE, null, 80, plugged = false, screenOn = false, batteryTemperatureDeciC = null)
    val met = PreconditionOutcome(true, 0, emptyList(), conditions)
    val unmet = PreconditionOutcome(false, 900_000, listOf("Device is charging; unplug it"), conditions.copy(plugged = true))
    val envelope = ComputeThermalEnvelopeUseCase()

    "the envelope verdict follows the gate" {
        val flat = List(20) { 10.0 }
        envelope(observations(flat, met))!!.validity shouldBe EnvelopeValidity.VALID
        envelope(observations(flat, unmet))!!.validity shouldBe EnvelopeValidity.PRECONDITIONS_UNMET
        envelope(observations(flat, null))!!.validity shouldBe EnvelopeValidity.NO_PRECONDITION_GATE
        envelope(observations(List(5) { 10.0 }, met))!!.validity shouldBe EnvelopeValidity.INSUFFICIENT_DATA
    }

    "throttling is dated from LIGHT and material throttling counted from MODERATE" {
        val status = { i: Int -> if (i >= 15) ThermalStatus.MODERATE else if (i >= 10) ThermalStatus.LIGHT else ThermalStatus.NONE }
        val e = envelope(observations(List(20) { 10.0 }, met, status))!!
        e.timeToFirstThrottleMillis shouldBe 10_000
        e.throttledIterations shouldBe 10
        e.materiallyThrottledIterations shouldBe 5
    }

    "decay is absent rather than invented when the run never settled" {
        val e = envelope(observations(List(30) { 30.0 - it * 0.5 }, met))!!
        e.steadyStateDecodeTokensPerSecond.shouldBeNull()
        e.decayPercent.shouldBeNull()
    }
})
