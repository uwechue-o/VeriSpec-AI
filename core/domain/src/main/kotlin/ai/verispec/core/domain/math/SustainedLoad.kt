package ai.verispec.core.domain.math

import kotlin.math.abs

/**
 * Peak and steady state of a throughput series, shared by the on-device
 * envelope and the host decay analysis so the two can never disagree. The
 * rules follow ThermalBench's analysis:
 *
 * - Peak is the best rolling-median window, not the single fastest iteration.
 *   One lucky iteration is noise; a sustained window is a capability.
 * - Steady state needs a settled tail. The last window must be steady: a
 *   small robust spread (MAD-based coefficient of variation), few outliers
 *   (iterations further than [OUTLIER_FRACTION] from the window median) and
 *   no drift (the medians of the window's two halves agree). The tail then
 *   reaches back through every earlier window that stays at that level and
 *   does not drift. Its value is the tail's median. If the last window is not
 *   steady the run never settled and there is no steady state, rather than
 *   reporting the end of a still-falling curve as a floor.
 * - The window grows with the run (1/30 of it, at least [STEADY_WINDOW]), so a
 *   long run is judged on a meaningful stretch rather than on 10 iterations.
 *
 * Since 2026-11-16. The earlier rule required every window of 10 to pass a
 * plain CV and least-squares slope test, so one isolated half-speed stall, or
 * three ordinary 8 % dips, broke the tail: a 3,000-iteration Pixel 6 run flat
 * at 21.8-22.0 inf/s, with about 70 isolated stalls, never "settled". It now
 * settles from iteration 0 at 21.9. Clustered stalls, alternating speeds and
 * a decline still running at the end are still rejected. A very slow drift
 * (well under 0.5 % across a window) is not detected, as before.
 */
object SustainedLoad {

    const val PEAK_WINDOW = 5
    const val STEADY_WINDOW = 10
    /** Runs longer than this many windows widen the window to keep it a fixed share of the run. */
    const val STEADY_WINDOWS_PER_RUN = 30
    const val MAX_STEADY_CV = 0.05
    const val MAX_STEADY_DRIFT = 0.03
    const val OUTLIER_FRACTION = 0.15

    /** Scales a median absolute deviation to a standard deviation for normally distributed data. */
    private const val MAD_TO_SD = 1.4826

    data class SteadyState(val startIndex: Int, val value: Double)

    /** Best median over any [window] consecutive values; the plain median when the series is shorter. */
    fun windowedPeak(values: List<Double>, window: Int = PEAK_WINDOW): Double {
        require(window > 0) { "window must be positive" }
        if (values.isEmpty()) return 0.0
        if (values.size < window) return Numerics.median(values)
        return (0..values.size - window).maxOf { Numerics.median(values.subList(it, it + window)) }
    }

    fun steadyWindow(iterations: Int): Int = maxOf(STEADY_WINDOW, iterations / STEADY_WINDOWS_PER_RUN)

    fun steadyState(
        values: List<Double>,
        window: Int = steadyWindow(values.size),
        maxCv: Double = MAX_STEADY_CV,
        maxDrift: Double = MAX_STEADY_DRIFT,
    ): SteadyState? {
        if (values.size < window) return null
        val last = values.subList(values.size - window, values.size)
        val level = Numerics.median(last)
        if (level <= 0.0) return null
        val deviations = last.map { abs(it - level) }
        if (MAD_TO_SD * Numerics.median(deviations) / level > maxCv) return null
        if (deviations.count { it > OUTLIER_FRACTION * level } > maxOutliers(window)) return null
        if (drift(last) > maxDrift) return null

        var start = values.size - window
        while (start > 0) {
            val w = values.subList(start - 1, start - 1 + window)
            if (abs(Numerics.median(w) - level) > maxCv * level || drift(w) > maxDrift) break
            start--
        }
        // Windows tolerate a few outliers, so the scan can reach back onto the last
        // steps of a decline; the tail starts at its first non-outlier instead.
        while (start < values.size - window && abs(values[start] - level) > OUTLIER_FRACTION * level) start++
        return SteadyState(start, Numerics.median(values.subList(start, values.size)))
    }

    /** Two in a window of 10: isolated stalls pass, a cluster does not. */
    internal fun maxOutliers(window: Int): Int = maxOf(2, window / 5)

    /** Relative difference between the medians of the window's two halves: a trend no single outlier can fake. */
    internal fun drift(values: List<Double>): Double {
        val half = values.size / 2
        val median = Numerics.median(values)
        if (half == 0 || median <= 0.0) return 0.0
        return abs(Numerics.median(values.subList(half, values.size)) - Numerics.median(values.subList(0, half))) / median
    }
}
