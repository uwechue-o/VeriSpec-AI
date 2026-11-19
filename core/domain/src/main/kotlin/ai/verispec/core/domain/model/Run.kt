package ai.verispec.core.domain.model

sealed interface RunFailure {
    val message: String

    data class InvalidManifest(val issues: List<ValidationIssue>) : RunFailure {
        override val message: String get() = "Manifest rejected: " + issues.joinToString("; ") { it.message }
    }

    data class BackendUnavailable(val runtime: RuntimeId, val reason: String?) : RunFailure {
        override val message: String get() = "Backend $runtime unavailable" + (reason?.let { ": $it" } ?: "")
    }

    data class DelegateUnavailable(val runtime: RuntimeId, val delegate: Delegate, val supported: Set<Delegate>) : RunFailure {
        override val message: String
            get() = "Delegate $delegate not offered by $runtime on this device (supported: ${supported.joinToString()})"
    }

    data class UnsupportedFormat(val runtime: RuntimeId, val format: ModelFormat) : RunFailure {
        override val message: String get() = "$runtime cannot load $format models"
    }

    data class ModelLoadFailed(val detail: String) : RunFailure {
        override val message: String get() = "Model load failed: $detail"
    }

    data class InferenceFailed(val iteration: Int, val detail: String) : RunFailure {
        override val message: String get() = "Inference failed at iteration $iteration: $detail"
    }

    data object Cancelled : RunFailure {
        override val message: String get() = "Run cancelled"
    }
}

sealed interface RunProgress {
    /** The precondition gate is still waiting; emitted on every poll. */
    data class AwaitingPreconditions(val state: PreconditionState) : RunProgress
    data class Started(val totalIterations: Int, val warmupIterations: Int, val capabilities: BackendCapabilities) : RunProgress
    data class ModelLoaded(val effectiveDelegate: Delegate, val runtimeVersionObserved: String?, val loadMillis: Long) : RunProgress
    data class Warmup(val index: Int, val total: Int) : RunProgress
    data class Iteration(
        val record: IterationRecord,
        val digest: OutputDigest,
        val liveThermal: ThermalStatus,
        val liveHeadroom: Float?,
        val completed: Int,
        val total: Int,
    ) : RunProgress {
        val fraction: Float get() = completed.toFloat() / total.toFloat()
    }
    data class Completed(val observations: Observations, val loaded: LoadedModel) : RunProgress
    data class Failed(val failure: RunFailure) : RunProgress
}

enum class Severity { ERROR, WARNING }

data class ValidationIssue(
    val field: String,
    val severity: Severity,
    val message: String,
)

data class ValidationResult(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()
}

/** Whether a performance envelope can be compared with others. */
enum class EnvelopeValidity {
    /** The precondition gate was satisfied and enough iterations ran. */
    VALID,

    /** The gate was not satisfied (it timed out and the manifest allowed the run anyway). */
    PRECONDITIONS_UNMET,

    /** The manifest had no gate, so the start state was recorded but not controlled. */
    NO_PRECONDITION_GATE,

    /** Too few measured iterations to describe sustained load. */
    INSUFFICIENT_DATA,
}

/** Sustained-load performance envelope (ThermalBench's measurement, see SustainedLoad). */
data class ThermalEnvelope(
    val iterationsAnalyzed: Int,
    /** Best rolling-median window, not the single fastest iteration. */
    val peakDecodeTokensPerSecond: Double,
    /** Median of the settled tail; null when the run never settled. */
    val steadyStateDecodeTokensPerSecond: Double?,
    /** First iteration of the settled tail; null when the run never settled. */
    val steadyStateFromIteration: Int?,
    /** (peak - steady) / peak, in percent; null without a steady state. */
    val decayPercent: Double?,
    /** First iteration (or timeline sample) at LIGHT or worse. */
    val timeToFirstThrottleMillis: Long?,
    /** Iterations that started or ended at LIGHT or worse. */
    val throttledIterations: Int,
    /** Iterations that started or ended at MODERATE or worse ([IterationRecord.wasThrottled]). */
    val materiallyThrottledIterations: Int,
    val maxThermalStatus: ThermalStatus,
    val minHeadroom: Float?,
    val meanTimeToFirstTokenMicros: Double,
    val p95TimeToFirstTokenMicros: Long,
    val validity: EnvelopeValidity,
)

/** Quantized-vs-reference accuracy delta over paired iterations. */
data class QuantizationDelta(
    val pairedIterations: Int,
    /** Fraction of iterations whose output digests were identical. */
    val digestAgreementRate: Double,
    val meanCosineSimilarity: Double?,
    val maxLInfDelta: Double?,
    val meanAbsoluteDelta: Double?,
)

/** Android's verified-boot states, as the secure hardware attests them. */
enum class VerifiedBootState { VERIFIED, SELF_SIGNED, UNVERIFIED, FAILED }

/** The attested boot state: hardware-enforced, so the OS cannot misreport it. */
data class RootOfTrust(
    val deviceLocked: Boolean,
    val verifiedBootState: VerifiedBootState,
) {
    /** A locked bootloader that booted an OS signed by the manufacturer. */
    val isVerified: Boolean get() = deviceLocked && verifiedBootState == VerifiedBootState.VERIFIED
}

/**
 * The app the attested key was made for, as Android's Keystore service records
 * it: package names and the SHA-256 of each signing certificate. The OS writes
 * this, not the secure hardware, so it is only as good as a verified boot.
 */
data class AttestedApp(
    val packageNames: List<String>,
    val signerDigests: List<Sha256>,
)

/** Apps whose bundles are accepted: every attested package listed, and at least one signer known. */
data class TrustedApps(
    val packageNames: Set<String>,
    val signerDigests: Set<Sha256>,
) {
    fun accepts(app: AttestedApp): Boolean =
        app.packageNames.isNotEmpty() &&
            app.packageNames.all { it in packageNames } &&
            app.signerDigests.any { it in signerDigests }

    operator fun plus(other: TrustedApps) =
        TrustedApps(packageNames + other.packageNames, signerDigests + other.signerDigests)
}

data class VerificationReport(
    val bundleId: BundleId,
    val canonicalHashMatches: Boolean,
    val signatureValid: Boolean,
    val attestationChainValid: Boolean,
    val hardwareBacked: Boolean,
    val strongBox: Boolean,
    val challengeBoundToManifest: Boolean,
    val schemaVersionSupported: Boolean,
    /** Locked bootloader and verified boot, read from the hardware-enforced attestation. */
    val verifiedBoot: Boolean,
    /** The attested app is a trusted package signed by a trusted key. */
    val appIdentityTrusted: Boolean,
    val rootOfTrust: RootOfTrust?,
    val attestedApp: AttestedApp?,
    val issues: List<String>,
) {
    /**
     * Evidence-grade only when hash, signature, chain, challenge, boot state and app
     * identity all check out. A key made for another manifest could otherwise bless this one.
     */
    val isTrustworthy: Boolean
        get() = canonicalHashMatches && signatureValid && attestationChainValid && schemaVersionSupported &&
            challengeBoundToManifest && verifiedBoot && appIdentityTrusted
}

/** One wording for the app, CLI and PDF: "verified, bootloader locked". */
fun VerificationReport.bootSummary(): String = rootOfTrust?.let {
    "${it.verifiedBootState.name.lowercase().replace('_', '-')}, bootloader ${if (it.deviceLocked) "locked" else "unlocked"}"
} ?: "not attested"

/** One wording for the app, CLI and PDF: "ai.verispec.app.debug, signer 0ea6f674bf933869… (trusted)". */
fun VerificationReport.appSummary(): String = attestedApp?.let { app ->
    val packages = app.packageNames.joinToString().ifEmpty { "no package" }
    val signers = app.signerDigests.joinToString { it.hex.take(16) + "…" }.ifEmpty { "none" }
    "$packages, signer $signers (${if (appIdentityTrusted) "trusted" else "NOT TRUSTED"})"
} ?: "not attested"
