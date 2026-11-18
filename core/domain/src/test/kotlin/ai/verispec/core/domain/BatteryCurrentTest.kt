package ai.verispec.core.domain

import ai.verispec.core.domain.math.BatteryCurrent
import ai.verispec.core.domain.model.PowerSample
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class BatteryCurrentTest : StringSpec({

    fun sample(current: Long?, mv: Int = 4_000, charging: Boolean = false) =
        PowerSample(elapsedMillis = 0, currentMicroAmps = current, voltageMilliVolts = mv, batteryLevelPercent = 80, isCharging = charging)

    "readings in the hundreds of thousands are microamperes" {
        // Pixel 6, 2026-11-14 (discharge reported negative).
        val samples = listOf(-294_687L, -1_491_250L, -1_784_687L).map { sample(it) }
        BatteryCurrent.inferUnit(samples) shouldBe BatteryCurrent.Unit.MICROAMPS
        BatteryCurrent.meanDischargePowerMilliWatts(samples)!! shouldBe (4_760.8 plusOrMinus 0.1)
    }

    "readings in the hundreds are milliamperes and are scaled" {
        // Honor Magic V3 and Galaxy Tab S7+, 2026-11-14.
        val samples = listOf(215L, 680L, 828L).map { sample(it) }
        BatteryCurrent.inferUnit(samples) shouldBe BatteryCurrent.Unit.MILLIAMPS
        BatteryCurrent.meanDischargePowerMilliWatts(samples)!! shouldBe (2_297.3 plusOrMinus 0.1)
    }

    "charging samples are left out of discharge power" {
        val samples = listOf(sample(-1_000_000L), sample(2_000_000L, charging = true))
        BatteryCurrent.meanDischargePowerMilliWatts(samples)!! shouldBe (4_000.0 plusOrMinus 0.001)
    }

    "no current samples means no unit and no power" {
        BatteryCurrent.inferUnit(listOf(sample(null))).shouldBeNull()
        BatteryCurrent.meanDischargePowerMilliWatts(listOf(sample(null))).shouldBeNull()
    }
})
