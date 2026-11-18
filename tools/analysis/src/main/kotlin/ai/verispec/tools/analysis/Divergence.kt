package ai.verispec.tools.analysis

import ai.verispec.core.domain.math.Numerics
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.RuntimeId

/** One device's participation in a divergence comparison. */
data class DeviceEntry(
    val bundleId: String,
    val fingerprint: String,
    val socVendor: String,
    val socModel: String,
    val runtime: RuntimeId,
    val runtimeVersionObserved: String?,
    val delegate: Delegate,
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
) {
    val shortLabel: String get() = "$socModel/$delegate"
}

/** Divergence of one device against the chosen reference device. */
data class PairwiseDivergence(
    val reference: DeviceEntry,
    val candidate: DeviceEntry,
    val comparedIterations: Int,
    /** Fraction of iterations whose output digests were byte-identical. */
    val digestAgreementRate: Double,
    val firstDivergentIteration: Int?,
    val meanCosineSimilarity: Double?,
    val minCosineSimilarity: Double?,
    val maxLInfDelta: Double?,
    val meanAbsoluteDelta: Double?,
    val maxSymmetricKl: Double?,
    val projectionAvailable: Boolean,
)

data class DivergenceReport(
    val manifestId: String,
    val label: String,
    val devices: List<DeviceEntry>,
    val reference: DeviceEntry,
    val pairwise: List<PairwiseDivergence>,
    val warnings: List<String>,
) {
    /** True when every device produced byte-identical outputs on every iteration. */
    val bitIdenticalAcrossFleet: Boolean get() = pairwise.all { it.digestAgreementRate >= 1.0 }

    val worstAgreement: Double get() = pairwise.minOfOrNull { it.digestAgreementRate } ?: 1.0
}

/**
 * Cross-device numerical divergence: identical input and model, N SoCs and
 * delegates, measured output drift.
 *
 * Lives on the JVM because it compares results across devices and therefore
 * cannot run on any one device. That split is load-bearing, not stylistic.
 */
class ComputeDivergenceUseCase {

    class IncomparableBundlesException(message: String) : IllegalArgumentException(message)

    /**
     * @param bundles two or more bundles that share a manifest id.
     * @param referenceBundleId which device is the baseline; defaults to the first.
     */
    operator fun invoke(bundles: List<EvidenceBundle>, referenceBundleId: String? = null): DivergenceReport {
        if (bundles.size < 2) throw IncomparableBundlesException("Divergence needs at least two bundles, got ${bundles.size}")
        val manifestIds = bundles.map { it.manifest.manifestId.value }.toSet()
        if (manifestIds.size != 1) {
            throw IncomparableBundlesException(
                "Bundles do not share a manifest; divergence is only meaningful for identical runs. Saw: " +
                    manifestIds.joinToString { it.take(12) },
            )
        }

        val warnings = mutableListOf<String>()
        val entries = bundles.associate { it.bundleId.value to it.toEntry() }
        val byId = bundles.associateBy { it.bundleId.value }

        val duplicateDevices = entries.values.groupBy { it.fingerprint }.filterValues { it.size > 1 }
        duplicateDevices.forEach { (fp, list) ->
            warnings += "${list.size} bundles from the same device (${fp.take(28)}…); divergence between them measures run-to-run noise, not hardware drift"
        }

        val referenceId = referenceBundleId ?: bundles.first().bundleId.value
        val reference = byId[referenceId]
            ?: throw IncomparableBundlesException("Reference bundle $referenceId is not in the supplied set")
        val referenceEntry = entries.getValue(referenceId)

        val pairwise = bundles
            .filter { it.bundleId.value != referenceId }
            .map { candidate -> compare(reference, candidate, referenceEntry, entries.getValue(candidate.bundleId.value), warnings) }

        return DivergenceReport(
            manifestId = manifestIds.first(),
            label = reference.manifest.label,
            devices = entries.values.sortedBy { it.shortLabel },
            reference = referenceEntry,
            pairwise = pairwise,
            warnings = warnings,
        )
    }

    private fun compare(
        reference: EvidenceBundle,
        candidate: EvidenceBundle,
        referenceEntry: DeviceEntry,
        candidateEntry: DeviceEntry,
        warnings: MutableList<String>,
    ): PairwiseDivergence {
        val refByIndex: Map<Int, OutputDigest> = reference.observations.outputDigests.associateBy { it.iterationIndex }
        val pairs = candidate.observations.outputDigests.mapNotNull { c -> refByIndex[c.iterationIndex]?.let { r -> r to c } }
        if (pairs.isEmpty()) {
            warnings += "No overlapping iterations between ${referenceEntry.shortLabel} and ${candidateEntry.shortLabel}"
            return PairwiseDivergence(referenceEntry, candidateEntry, 0, 1.0, null, null, null, null, null, null, false)
        }

        val agreeing = pairs.count { (r, c) -> r.sha256 == c.sha256 }
        val firstDivergent = pairs.firstOrNull { (r, c) -> r.sha256 != c.sha256 }?.first?.iterationIndex

        val projected = pairs.filter { (r, c) ->
            val a = r.logitProjection
            val b = c.logitProjection
            a != null && b != null && a.isNotEmpty() && a.size == b.size
        }
        if (projected.isEmpty()) {
            if (pairs.any { it.first.logitProjection != null || it.second.logitProjection != null }) {
                warnings += "Logit projections present but not comparable between ${referenceEntry.shortLabel} and ${candidateEntry.shortLabel} (length mismatch)"
            }
            return PairwiseDivergence(
                reference = referenceEntry,
                candidate = candidateEntry,
                comparedIterations = pairs.size,
                digestAgreementRate = agreeing.toDouble() / pairs.size,
                firstDivergentIteration = firstDivergent,
                meanCosineSimilarity = null,
                minCosineSimilarity = null,
                maxLInfDelta = null,
                meanAbsoluteDelta = null,
                maxSymmetricKl = null,
                projectionAvailable = false,
            )
        }

        val cosines = projected.map { (r, c) -> Numerics.cosineSimilarity(r.logitProjection!!, c.logitProjection!!) }
        val linf = projected.map { (r, c) -> Numerics.lInfDistance(r.logitProjection!!, c.logitProjection!!) }
        val mad = projected.map { (r, c) -> Numerics.meanAbsoluteDifference(r.logitProjection!!, c.logitProjection!!) }
        val kl = projected.map { (r, c) -> Numerics.symmetricKlDivergence(r.logitProjection!!, c.logitProjection!!) }

        return PairwiseDivergence(
            reference = referenceEntry,
            candidate = candidateEntry,
            comparedIterations = pairs.size,
            digestAgreementRate = agreeing.toDouble() / pairs.size,
            firstDivergentIteration = firstDivergent,
            meanCosineSimilarity = Numerics.mean(cosines),
            minCosineSimilarity = cosines.min(),
            maxLInfDelta = linf.max(),
            meanAbsoluteDelta = Numerics.mean(mad),
            maxSymmetricKl = kl.max(),
            projectionAvailable = true,
        )
    }

    private fun EvidenceBundle.toEntry() = DeviceEntry(
        bundleId = bundleId.value,
        fingerprint = device.fingerprint,
        socVendor = device.soc.vendor,
        socModel = device.soc.model,
        runtime = provenance.runtime,
        runtimeVersionObserved = provenance.runtimeVersionObserved,
        delegate = provenance.delegateActuallyUsed,
        npuDriverVersion = provenance.npuDriverVersion,
        gpuDriverVersion = provenance.gpuDriverVersion,
    )
}
