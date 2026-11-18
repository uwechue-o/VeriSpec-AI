package ai.verispec.core.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 1.2.0 hardware telemetry reduced to what a reader scans for: did the battery
 * heat up, did the clocks drop, did the screen stay in its required state. One
 * reduction shared by the console, the CLI and the PDF so they cannot disagree.
 */
data class HardwareSummary(
    /** Lowest and highest battery temperature over the power timeline, in tenths of a degree. */
    val batteryTemperatureDeciC: IntRange?,
    /** Range of the fastest core's clock across iterations; it falls when the big cluster is throttled. */
    val fastestCoreKhz: LongRange?,
    val gpuKhz: LongRange?,
    /** Share of power samples with the screen on, 0..1. */
    val screenOnFraction: Double?,
    val inventory: TelemetryInventory?,
) {
    /** Label/value rows, formatted once for every renderer. Absent signals say so rather than vanish. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("battery temperature" to (batteryTemperatureDeciC?.let { "${deg(it.first)}–${deg(it.last)} °C" } ?: "not recorded"))
        add("fastest core clock" to (fastestCoreKhz?.let { "${mhz(it.first)}–${mhz(it.last)} MHz" } ?: "not readable"))
        add("GPU clock" to (gpuKhz?.let { "${mhz(it.first)}–${mhz(it.last)} MHz" } ?: "not readable"))
        add("screen on" to (screenOnFraction?.let { "${(it * 100).roundToInt()} % of samples" } ?: "not recorded"))
        inventory?.unavailable?.forEach { add("unavailable" to it) }
    }

    companion object {
        private fun deg(deciC: Int) = "${if (deciC < 0) "-" else ""}${abs(deciC) / 10}.${abs(deciC) % 10}"
        private fun mhz(khz: Long) = (khz / 1_000).toString()

        /** Null for bundles without any hardware telemetry, i.e. from before 1.2.0. */
        fun of(o: Observations): HardwareSummary? {
            val battery = o.powerTimeline.mapNotNull { it.batteryTemperatureDeciC }
            val fastest = o.iterations.mapNotNull { r -> r.clocks?.cpuKhz?.maxOrNull() }
            val gpu = o.iterations.mapNotNull { it.clocks?.gpuKhz }
            val screen = o.powerTimeline.mapNotNull { it.screenOn }
            val summary = HardwareSummary(
                batteryTemperatureDeciC = if (battery.isEmpty()) null else battery.min()..battery.max(),
                fastestCoreKhz = longRange(fastest),
                gpuKhz = longRange(gpu),
                screenOnFraction = if (screen.isEmpty()) null else screen.count { it }.toDouble() / screen.size,
                inventory = o.telemetry,
            )
            return summary.takeUnless { it == HardwareSummary(null, null, null, null, null) }
        }

        private fun longRange(values: List<Long>): LongRange? = if (values.isEmpty()) null else values.min()..values.max()
    }
}
