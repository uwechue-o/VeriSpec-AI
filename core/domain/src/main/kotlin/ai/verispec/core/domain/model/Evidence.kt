package ai.verispec.core.domain.model

/**
 * Provenance: hashes of the model artifact, runtime version, delegate and the
 * NPU/GPU driver versions the run actually executed against.
 */
data class ProvenanceRecord(
    val modelSha256: Sha256,
    val modelFormat: ModelFormat,
    val runtime: RuntimeId,
    val runtimeVersionDeclared: SemVer,
    /** What the backend itself reported at load time; may differ from the manifest. */
    val runtimeVersionObserved: String?,
    val delegate: Delegate,
    val delegateActuallyUsed: Delegate,
    val npuDriverVersion: String?,
    val gpuDriverVersion: String?,
    val toolVersion: SemVer,
    val capturedAtEpochMillis: Long,
    /** Recorded degradations: e.g. "StrongBox unavailable, fell back to TEE". */
    val notes: List<String> = emptyList(),
    /** The device the manifest named behind the delegate; null when none was named. */
    val accelerator: String? = null,
    /** The named device the delegate actually ran on; null when the platform chose or the name was refused. */
    val acceleratorActuallyUsed: String? = null,
    /** Every device the delegate's platform API listed; null when not listed (non-NNAPI runs, older bundles). */
    val acceleratorsAvailable: List<AcceleratorDevice>? = null,
)

/**
 * The accelerator part of the provenance in words, the same in the app, the CLI and
 * the PDF; null when the run neither named a device nor listed any (non-NNAPI runs and
 * bundles from before 1.3.0). An unnamed NNAPI run is never credited to a device.
 */
fun ProvenanceRecord.acceleratorSummary(): String? {
    val listed = acceleratorsAvailable?.joinToString(", ") { "${it.name} (${it.type.name.lowercase()})" }
    if (accelerator == null && listed == null) return null
    val choice = when {
        accelerator == null -> "none named, so NNAPI chose and did not report which device"
        acceleratorActuallyUsed == accelerator -> "$accelerator named, delegate bound to it"
        else -> "$accelerator named but not used"
    }
    return if (listed == null) choice else "$choice; listed: $listed"
}

/** DER-encoded X.509 certificate. Kept as bytes so the domain stays framework-free. */
class EncodedCertificate(val der: ByteArray) {
    override fun equals(other: Any?): Boolean = other is EncodedCertificate && der.contentEquals(other.der)
    override fun hashCode(): Int = der.contentHashCode()
    override fun toString(): String = "EncodedCertificate(${der.size} bytes)"
}

class DetachedSignature(
    /** JCA-style algorithm name, e.g. "SHA256withECDSA". */
    val algorithm: String,
    val signature: ByteArray,
    /** SHA-256 of the leaf certificate's SubjectPublicKeyInfo. */
    val keyId: Sha256,
    val signedAtEpochMillis: Long,
    val hardwareBacked: Boolean,
    val strongBox: Boolean,
    /** Attestation challenge the key was generated with; must equal the manifest hash. */
    val attestationChallenge: Sha256?,
) {
    override fun equals(other: Any?): Boolean =
        other is DetachedSignature &&
            algorithm == other.algorithm &&
            signature.contentEquals(other.signature) &&
            keyId == other.keyId &&
            signedAtEpochMillis == other.signedAtEpochMillis &&
            hardwareBacked == other.hardwareBacked &&
            strongBox == other.strongBox &&
            attestationChallenge == other.attestationChallenge

    override fun hashCode(): Int {
        var h = algorithm.hashCode()
        h = 31 * h + signature.contentHashCode()
        h = 31 * h + keyId.hashCode()
        h = 31 * h + signedAtEpochMillis.hashCode()
        h = 31 * h + hardwareBacked.hashCode()
        h = 31 * h + strongBox.hashCode()
        h = 31 * h + (attestationChallenge?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String = "DetachedSignature($algorithm, key=${keyId.short}, hw=$hardwareBacked, sb=$strongBox)"
}

/** The bundle content that gets canonicalised, hashed and signed. */
data class UnsignedBundle(
    val schemaVersion: SemVer,
    val manifest: RunManifest,
    val device: DeviceProfile,
    val provenance: ProvenanceRecord,
    val observations: Observations,
    val clauseCoverage: List<ClauseCoverage>,
)

/** The output artifact. Signed, canonical, self-describing. */
data class EvidenceBundle(
    val bundleId: BundleId,
    val schemaVersion: SemVer,
    val manifest: RunManifest,
    val device: DeviceProfile,
    val provenance: ProvenanceRecord,
    val observations: Observations,
    val clauseCoverage: List<ClauseCoverage>,
    val signature: DetachedSignature,
    val attestationChain: List<EncodedCertificate>,
) {
    /** The exact content that was hashed and signed. */
    fun unsigned(): UnsignedBundle = UnsignedBundle(
        schemaVersion = schemaVersion,
        manifest = manifest,
        device = device,
        provenance = provenance,
        observations = observations,
        clauseCoverage = clauseCoverage,
    )

    companion object {
        /**
         * Bump the major on any breaking change to the canonical wire format, the
         * minor on an additive one. 1.1.0 added the optional precondition fields,
         * which are absent (never null) when unused, so 1.0.0 bundles still verify.
         * 1.2.0 added, the same way, battery temperature and screen state per power
         * sample, CPU/GPU clocks per iteration, the telemetry inventory and the
         * ON_DIMMED screen policy. 1.3.0 added, the same way, the named accelerator
         * behind a delegate (manifest and provenance), the one actually used and the
         * devices the platform listed.
         */
        val CURRENT_SCHEMA_VERSION = SemVer(1, 3, 0)
    }
}

enum class SignatureStatus { VALID, INVALID, UNVERIFIED, NO_HARDWARE_BACKING }

data class BundleSummary(
    val bundleId: BundleId,
    val manifestId: ManifestId,
    val label: String,
    val deviceFingerprint: String,
    val socModel: String,
    val runtime: RuntimeId,
    val delegate: Delegate,
    val quantization: String,
    val createdAtEpochMillis: Long,
    val iterationCount: Int,
    val meanDecodeTokensPerSecond: Double,
    val throttledIterations: Int,
    val signatureStatus: SignatureStatus,
    val coveredClauses: Int,
    val totalClauses: Int,
    /** True when this bundle was produced on another device and imported here. */
    val imported: Boolean,
)

enum class ExportFormat { JSON, PDF }

class ExportedArtifact(
    val format: ExportFormat,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun toString(): String = "ExportedArtifact($fileName, ${bytes.size} bytes)"
}
