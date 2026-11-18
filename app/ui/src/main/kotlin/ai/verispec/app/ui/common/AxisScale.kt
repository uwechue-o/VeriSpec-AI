package ai.verispec.app.ui.common

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * One chart axis: a range widened to round numbers and the ticks inside it.
 * Ticks fall on 1, 2, 2.5 or 5 × 10ⁿ so labels read as plain values. Pure, so
 * the live screen and the bundle screen label the same data the same way.
 */
data class AxisScale(val min: Double, val max: Double, val step: Double, val ticks: List<Double>) {

    val span: Double get() = max - min

    /** Position of [value] along the axis, 0 at [min] and 1 at [max]. */
    fun fraction(value: Double): Double = if (span <= 0.0) 0.0 else (value - min) / span

    /** Tick label with only as many decimals as the step needs (0, 0.5, 1 rather than 0.0, 0.5, 1.0). */
    fun label(value: Double): String {
        for (decimals in 0..4) {
            val text = "%.${decimals}f".format(value)
            if (abs(text.toDouble() - value) <= step * 1e-6) return text
        }
        return "%.4f".format(value)
    }

    companion object {
        private val MANTISSAS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /**
         * Scale covering [lo]..[hi]. [fromZero] keeps 0 on the axis, which suits
         * throughput and clocks (a drop reads in proportion); temperatures want a
         * tight range instead. [minStep] stops an iteration axis ticking at 0.5.
         */
        fun of(lo: Double, hi: Double, fromZero: Boolean, targetTicks: Int = 4, minStep: Double = 0.0): AxisScale {
            var low = if (fromZero) min(0.0, lo) else lo
            var high = if (fromZero) max(0.0, hi) else hi
            if (high <= low) {
                // A flat series still needs a visible band around it.
                val pad = max(1.0, abs(high) * 0.1)
                high += pad
                if (!fromZero || low < 0.0) low -= pad
            }
            val step = max(niceStep((high - low) / targetTicks), minStep)
            val first = floor(low / step + 1e-9)
            val last = ceil(high / step - 1e-9)
            val ticks = (0..(last - first).roundToLong().toInt()).map { (first + it) * step }
            return AxisScale(first * step, last * step, step, ticks)
        }

        internal fun niceStep(raw: Double): Double {
            if (raw <= 0.0 || raw.isNaN()) return 1.0
            val magnitude = 10.0.pow(floor(log10(raw)))
            val mantissa = MANTISSAS.first { it * magnitude >= raw * (1 - 1e-9) }
            return mantissa * magnitude
        }
    }
}
