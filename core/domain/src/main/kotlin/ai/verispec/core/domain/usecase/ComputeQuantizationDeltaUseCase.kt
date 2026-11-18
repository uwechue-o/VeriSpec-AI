package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.math.Numerics
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.QuantizationDelta

/**
 * Quantized vs FP-reference accuracy delta. Both runs must share a manifest
 * workload and seed so digests pair up by iteration index.
 */
class ComputeQuantizationDeltaUseCase {

    operator fun invoke(quantized: List<OutputDigest>, reference: List<OutputDigest>): QuantizationDelta {
        val refByIndex = reference.associateBy { it.iterationIndex }
        val pairs = quantized.mapNotNull { q -> refByIndex[q.iterationIndex]?.let { q to it } }
        if (pairs.isEmpty()) {
            return QuantizationDelta(0, 0.0, null, null, null)
        }

        val agreement = pairs.count { (q, r) -> q.sha256 == r.sha256 }.toDouble() / pairs.size

        val projected = pairs.filter { (q, r) ->
            val a = q.logitProjection
            val b = r.logitProjection
            a != null && b != null && a.size == b.size && a.isNotEmpty()
        }

        if (projected.isEmpty()) {
            return QuantizationDelta(pairs.size, agreement, null, null, null)
        }

        val cosines = projected.map { (q, r) -> Numerics.cosineSimilarity(q.logitProjection!!, r.logitProjection!!) }
        val linf = projected.map { (q, r) -> Numerics.lInfDistance(q.logitProjection!!, r.logitProjection!!) }
        val mad = projected.map { (q, r) -> Numerics.meanAbsoluteDifference(q.logitProjection!!, r.logitProjection!!) }

        return QuantizationDelta(
            pairedIterations = pairs.size,
            digestAgreementRate = agreement,
            meanCosineSimilarity = Numerics.mean(cosines),
            maxLInfDelta = linf.max(),
            meanAbsoluteDelta = Numerics.mean(mad),
        )
    }
}
