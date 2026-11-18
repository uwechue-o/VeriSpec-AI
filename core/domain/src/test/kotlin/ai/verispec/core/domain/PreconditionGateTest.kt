package ai.verispec.core.domain

import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.model.PreconditionPolicy
import ai.verispec.core.domain.model.PreconditionState
import ai.verispec.core.domain.model.ScreenPolicy
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.model.UnmetCondition
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.domain.port.DeviceConditionSource
import ai.verispec.core.domain.usecase.AwaitPreconditionsUseCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class PreconditionGateTest : StringSpec({

    val cold = DeviceConditions(ThermalStatus.NONE, 0.2f, 80, plugged = false, screenOn = false, batteryTemperatureDeciC = 290)
    val policy = PreconditionPolicy.DEFAULT

    "a cold, unplugged, charged device with the screen off passes the default policy" {
        policy.evaluate(cold).shouldBeEmpty()
    }

    "each condition blocks on its own" {
        policy.evaluate(cold.copy(plugged = true)) shouldBe listOf(UnmetCondition.Charging)
        policy.evaluate(cold.copy(thermalStatus = ThermalStatus.LIGHT)).single().shouldBeInstanceOf<UnmetCondition.ThermalStatusTooHigh>()
        policy.evaluate(cold.copy(batteryPercent = 20)).single().shouldBeInstanceOf<UnmetCondition.BatteryTooLow>()
        policy.evaluate(cold.copy(screenOn = true)).single().shouldBeInstanceOf<UnmetCondition.ScreenState>()
        // Platform headroom 0.8 means close to throttling: a margin of 0.2, below the 0.5 required.
        policy.evaluate(cold.copy(thermalHeadroom = 0.8f)).single().shouldBeInstanceOf<UnmetCondition.HeadroomTooLow>()
    }

    "an unknown thermal status or battery level is never read as fine" {
        policy.evaluate(cold.copy(thermalStatus = ThermalStatus.UNKNOWN)).single().shouldBeInstanceOf<UnmetCondition.ThermalStatusTooHigh>()
        policy.evaluate(cold.copy(batteryPercent = null)).single().shouldBeInstanceOf<UnmetCondition.BatteryTooLow>()
    }

    "missing headroom does not block, so devices that never report it stay usable" {
        policy.evaluate(cold.copy(thermalHeadroom = null)).shouldBeEmpty()
    }

    "the screen policy is enforced in both directions" {
        val on = policy.copy(screenPolicy = ScreenPolicy.ON)
        on.evaluate(cold).single().shouldBeInstanceOf<UnmetCondition.ScreenState>()
        on.evaluate(cold.copy(screenOn = true)).shouldBeEmpty()
    }

    "ON_DIMMED needs the screen on and says the app holds it" {
        val dimmed = policy.copy(screenPolicy = ScreenPolicy.ON_DIMMED)
        val unmet = dimmed.evaluate(cold).single()
        unmet.shouldBeInstanceOf<UnmetCondition.ScreenState>()
        unmet.description shouldBe "Screen is off; turn it on and keep VeriSpec open. It holds the screen on, dimmed"
        dimmed.evaluate(cold.copy(screenOn = true)).shouldBeEmpty()
    }

    "margins are rounded down, never shown as reaching a threshold they have not" {
        UnmetCondition.HeadroomTooLow(0.499f, 0.5f).description shouldBe "Thermal headroom margin is 0.49; need >= 0.50"
        UnmetCondition.HeadroomTooLow(1f - 0.6f, 0.5f).description shouldBe "Thermal headroom margin is 0.40; need >= 0.50"
    }

    /** Collects the gate under virtual time: one reading per poll, the last one repeated. */
    suspend fun TestScope.gate(readings: List<DeviceConditions>, p: PreconditionPolicy): List<PreconditionState> {
        val clock = object : Clock {
            override fun epochMillis() = 0L
            override fun monotonicNanos() = testScheduler.currentTime * 1_000_000
        }
        var i = 0
        val source = object : DeviceConditionSource {
            override suspend fun read() = readings[minOf(i++, readings.lastIndex)]
        }
        return AwaitPreconditionsUseCase(source, clock, pollIntervalMillis = 1_000)(p).toList()
    }

    "the gate is satisfied only after the window holds without interruption" {
        runTest {
            val p = policy.copy(stabilizationWindowMillis = 3_000, maxWaitMillis = 60_000)
            // Cold, cold, charging (window restarts), then cold from t=3s on.
            val states = gate(listOf(cold, cold, cold.copy(plugged = true)) + List(10) { cold }, p)
            val done = states.last().shouldBeInstanceOf<PreconditionState.Satisfied>()
            done.waitedMillis shouldBe 6_000
            states.filterIsInstance<PreconditionState.Waiting>().map { it.unmet } shouldContain listOf(UnmetCondition.Charging)
        }
    }

    "the gate times out with the conditions still unmet" {
        runTest {
            val p = policy.copy(stabilizationWindowMillis = 0, maxWaitMillis = 5_000)
            val timedOut = gate(listOf(cold.copy(plugged = true)), p).last().shouldBeInstanceOf<PreconditionState.TimedOut>()
            timedOut.unmet shouldBe listOf(UnmetCondition.Charging)
            timedOut.waitedMillis shouldBe 5_000
        }
    }
})
