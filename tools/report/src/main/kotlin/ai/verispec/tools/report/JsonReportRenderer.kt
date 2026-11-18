package ai.verispec.tools.report

import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.VerificationReport
import ai.verispec.core.evidence.canonical.CanonicalJson
import ai.verispec.core.evidence.wire.toDto
import ai.verispec.tools.analysis.DecayAnalysis
import ai.verispec.tools.analysis.DivergenceReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Machine-readable companion to the PDF. This is an ANALYSIS document, not an
 * evidence bundle: it is derived, not signed. The bundle it was derived from is
 * referenced by hash so the two never drift apart.
 */
@Serializable
data class AnalysisDocument(
    val documentType: String = DOCUMENT_TYPE,
    val schemaVersion: String = SCHEMA_VERSION,
    val bundleId: String,
    val manifestId: String,
    val label: String,
    val verification: VerificationDto?,
    val decay: DecayDto?,
    val divergence: DivergenceDto?,
    val clauseSummary: ClauseSummaryDto,
) {
    companion object {
        const val DOCUMENT_TYPE = "ai.verispec.analysis-report"
        const val SCHEMA_VERSION = "1.0.0"
    }
}

@Serializable
data class VerificationDto(
    val trustworthy: Boolean,
    val canonicalHashMatches: Boolean,
    val signatureValid: Boolean,
    val attestationChainValid: Boolean,
    val hardwareBacked: Boolean,
    val strongBox: Boolean,
    val challengeBoundToManifest: Boolean,
    val issues: List<String>,
)

@Serializable
data class DecayDto(
    val iterations: Int,
    val peakTokensPerSecond: Double,
    /** Null when the run never settled. */
    val steadyStateTokensPerSecond: Double?,
    val steadyStateFromIteration: Int?,
    val decayPercent: Double?,
    val slopePerIteration: Double,
    val rSquared: Double,
    val timeToFirstThrottleMillis: Long?,
    val throttledFraction: Double,
    val materiallyThrottledFraction: Double,
    val maxThermalStatus: String,
    val minHeadroom: Float?,
    val p95TimeToFirstTokenMicros: Double,
    val p99TimeToFirstTokenMicros: Double,
    val coefficientOfVariation: Double,
    val meanPowerMilliWatts: Double?,
    val energyPerTokenMilliJoules: Double?,
    /** MICROAMPS or MILLIAMPS: how the device's raw battery current was read. */
    val batteryCurrentUnit: String? = null,
    val validity: String,
)

@Serializable
data class DivergencePairDto(
    val referenceBundleId: String,
    val candidateBundleId: String,
    val referenceDevice: String,
    val candidateDevice: String,
    val comparedIterations: Int,
    val digestAgreementRate: Double,
    val firstDivergentIteration: Int?,
    val meanCosineSimilarity: Double?,
    val minCosineSimilarity: Double?,
    val maxLInfDelta: Double?,
    val meanAbsoluteDelta: Double?,
    val maxSymmetricKl: Double?,
    val projectionAvailable: Boolean,
)

@Serializable
data class DivergenceDto(
    val manifestId: String,
    val deviceCount: Int,
    val bitIdenticalAcrossFleet: Boolean,
    val worstAgreementRate: Double,
    val pairs: List<DivergencePairDto>,
    val warnings: List<String>,
)

@Serializable
data class ClauseSummaryDto(
    val total: Int,
    val satisfied: Int,
    val partial: Int,
    val notCovered: Int,
    val gaps: List<String>,
)

class JsonReportRenderer {

    private val json = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }
    private val pretty = Json(from = json) { prettyPrint = true; prettyPrintIndent = "  " }

    fun build(
        bundle: EvidenceBundle,
        decay: DecayAnalysis?,
        verification: VerificationReport?,
        divergence: DivergenceReport?,
    ): AnalysisDocument = AnalysisDocument(
        bundleId = bundle.bundleId.value,
        manifestId = bundle.manifest.manifestId.value,
        label = bundle.manifest.label,
        verification = verification?.let {
            VerificationDto(
                trustworthy = it.isTrustworthy,
                canonicalHashMatches = it.canonicalHashMatches,
                signatureValid = it.signatureValid,
                attestationChainValid = it.attestationChainValid,
                hardwareBacked = it.hardwareBacked,
                strongBox = it.strongBox,
                challengeBoundToManifest = it.challengeBoundToManifest,
                issues = it.issues,
            )
        },
        decay = decay?.let {
            DecayDto(
                iterations = it.iterations,
                peakTokensPerSecond = it.peakTokensPerSecond,
                steadyStateTokensPerSecond = it.steadyStateTokensPerSecond,
                steadyStateFromIteration = it.steadyStateFromIteration,
                decayPercent = it.decayPercent,
                slopePerIteration = it.slopePerIteration,
                rSquared = it.rSquared,
                timeToFirstThrottleMillis = it.timeToFirstThrottleMillis,
                throttledFraction = it.throttledFraction,
                materiallyThrottledFraction = it.materiallyThrottledFraction,
                maxThermalStatus = it.maxThermalStatus.name,
                minHeadroom = it.minHeadroom,
                p95TimeToFirstTokenMicros = it.p95TimeToFirstTokenMicros,
                p99TimeToFirstTokenMicros = it.p99TimeToFirstTokenMicros,
                coefficientOfVariation = it.coefficientOfVariation,
                meanPowerMilliWatts = it.meanPowerMilliWatts,
                energyPerTokenMilliJoules = it.energyPerTokenMilliJoules,
                batteryCurrentUnit = it.batteryCurrentUnit?.name,
                validity = it.validity.name,
            )
        },
        divergence = divergence?.let { d ->
            DivergenceDto(
                manifestId = d.manifestId,
                deviceCount = d.devices.size,
                bitIdenticalAcrossFleet = d.bitIdenticalAcrossFleet,
                worstAgreementRate = d.worstAgreement,
                pairs = d.pairwise.map {
                    DivergencePairDto(
                        referenceBundleId = it.reference.bundleId,
                        candidateBundleId = it.candidate.bundleId,
                        referenceDevice = it.reference.shortLabel,
                        candidateDevice = it.candidate.shortLabel,
                        comparedIterations = it.comparedIterations,
                        digestAgreementRate = it.digestAgreementRate,
                        firstDivergentIteration = it.firstDivergentIteration,
                        meanCosineSimilarity = it.meanCosineSimilarity,
                        minCosineSimilarity = it.minCosineSimilarity,
                        maxLInfDelta = it.maxLInfDelta,
                        meanAbsoluteDelta = it.meanAbsoluteDelta,
                        maxSymmetricKl = it.maxSymmetricKl,
                        projectionAvailable = it.projectionAvailable,
                    )
                },
                warnings = d.warnings,
            )
        },
        clauseSummary = ClauseSummaryDto(
            total = bundle.clauseCoverage.size,
            satisfied = bundle.clauseCoverage.count { it.status.name == "SATISFIED" },
            partial = bundle.clauseCoverage.count { it.status.name == "PARTIAL" },
            notCovered = bundle.clauseCoverage.count { it.status.name == "NOT_COVERED" },
            gaps = bundle.clauseCoverage.filter { it.status.name != "SATISFIED" }
                .map { "${it.framework.name} ${it.reference}: ${it.gapDescription ?: "not evidenced"}" },
        ),
    )

    /** Canonical bytes so an analysis document is itself reproducible. */
    fun renderCanonical(document: AnalysisDocument): ByteArray =
        CanonicalJson.encode(json.encodeToJsonElement(AnalysisDocument.serializer(), document))

    fun renderPretty(document: AnalysisDocument): String =
        pretty.encodeToString(AnalysisDocument.serializer(), document)

    /** Re-serialises the bundle itself, unchanged, for binder attachments. */
    fun renderBundle(bundle: EvidenceBundle): String =
        pretty.encodeToString(ai.verispec.core.evidence.wire.EvidenceBundleDto.serializer(), bundle.toDto())
}
