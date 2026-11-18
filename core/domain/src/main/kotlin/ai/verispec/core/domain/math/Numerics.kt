package ai.verispec.core.domain.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small, dependency-free numerics shared by the on-device use cases and the
 * JVM analysis module. Deterministic: no parallel reductions, fixed order.
 */
object Numerics {

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector length mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return if (na == nb) 1.0 else 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun lInfDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector length mismatch: ${a.size} vs ${b.size}" }
        var max = 0.0
        for (i in a.indices) {
            val d = abs(a[i].toDouble() - b[i].toDouble())
            if (d > max) max = d
        }
        return max
    }

    fun meanAbsoluteDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vector length mismatch: ${a.size} vs ${b.size}" }
        if (a.isEmpty()) return 0.0
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i].toDouble() - b[i].toDouble())
        return sum / a.size
    }

    /** Symmetric KL divergence between two softmax-normalised vectors. */
    fun symmetricKlDivergence(a: FloatArray, b: FloatArray, epsilon: Double = 1e-9): Double {
        require(a.size == b.size) { "Vector length mismatch: ${a.size} vs ${b.size}" }
        val p = softmax(a)
        val q = softmax(b)
        var kl = 0.0
        for (i in p.indices) {
            val pi = p[i] + epsilon
            val qi = q[i] + epsilon
            kl += pi * kotlin.math.ln(pi / qi) + qi * kotlin.math.ln(qi / pi)
        }
        return kl / 2.0
    }

    fun softmax(v: FloatArray): DoubleArray {
        if (v.isEmpty()) return DoubleArray(0)
        val max = v.maxOf { it.toDouble() }
        val exps = DoubleArray(v.size) { kotlin.math.exp(v[it].toDouble() - max) }
        val sum = exps.sum()
        return DoubleArray(v.size) { exps[it] / sum }
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) 0.0 else values.sum() / values.size

    /** Median over a sorted copy; average of the two middle values for even sizes. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /** Nearest-rank percentile, p in [0, 100]. */
    fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0
        require(p in 0.0..100.0) { "percentile must be within [0, 100]" }
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Least-squares slope of [values] against their index (0, 1, 2 ...). Zero for fewer than two values. */
    fun slope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0
        val mx = (n - 1) / 2.0
        val my = mean(values)
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            val dx = i - mx
            sxy += dx * (values[i] - my)
            sxx += dx * dx
        }
        return sxy / sxx
    }

    fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / (values.size - 1))
    }
}
