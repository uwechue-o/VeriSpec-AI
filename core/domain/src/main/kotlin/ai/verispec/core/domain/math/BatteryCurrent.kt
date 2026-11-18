package ai.verispec.core.domain.math

import ai.verispec.core.domain.model.PowerSample
import kotlin.math.abs

/**
 * BatteryManager.BATTERY_PROPERTY_CURRENT_NOW is documented in microamperes,
 * but some vendors report milliamperes. Bundles keep the raw value, so the
 * unit is inferred here, once per run, wherever power is derived.
 *
 * The two populations do not overlap: a phone under load draws hundreds of
 * milliamperes, which is 10^5-10^6 in microamperes but 10^2-10^3 when the
 * vendor reports milliamperes. On 2026-11-14 the Pixel 6 read 294,687 to
 * 2,618,437; the Honor Magic V3 215 to 828 and the Galaxy Tab S7+ 675 to
 * 2,203. A run whose median magnitude is below [MILLIAMP_CEILING] is therefore
 * read as milliamperes: in microamperes that would be under 20 mA, less than
 * an idle phone draws.
 */
object BatteryCurrent {

    enum class Unit(val toMicroAmps: Long) { MICROAMPS(1), MILLIAMPS(1_000) }

    const val MILLIAMP_CEILING = 20_000L

    /** The unit the run's raw currents are in, or null when it recorded none. */
    fun inferUnit(samples: List<PowerSample>): Unit? {
        val magnitudes = samples.mapNotNull { it.currentMicroAmps?.let(::abs)?.toDouble() }.filter { it > 0.0 }
        if (magnitudes.isEmpty()) return null
        return if (Numerics.median(magnitudes) < MILLIAMP_CEILING) Unit.MILLIAMPS else Unit.MICROAMPS
    }

    /**
     * Mean battery power while discharging, in milliwatts, with the unit
     * inferred from the whole run. Charging samples are left out: the current
     * then flows into the battery and says nothing about the workload.
     */
    fun meanDischargePowerMilliWatts(samples: List<PowerSample>): Double? {
        val unit = inferUnit(samples) ?: return null
        val watts = samples.filter { !it.isCharging }.mapNotNull { s ->
            val current = s.currentMicroAmps ?: return@mapNotNull null
            val volts = s.voltageMilliVolts ?: return@mapNotNull null
            abs(current.toDouble()) * unit.toMicroAmps / 1_000.0 * volts / 1_000.0
        }
        return watts.takeIf { it.isNotEmpty() }?.average()
    }
}
