package ai.verispec.core.domain.port

import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.BundleSummary
import ai.verispec.core.domain.model.CatalogVersion
import ai.verispec.core.domain.model.ClauseDefinition
import ai.verispec.core.domain.model.ClockReading
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.DetachedSignature
import ai.verispec.core.domain.model.DeviceConditions
import ai.verispec.core.domain.model.DeviceProfile
import ai.verispec.core.domain.model.EncodedCertificate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.ExportFormat
import ai.verispec.core.domain.model.ExportedArtifact
import ai.verispec.core.domain.model.InferenceResult
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.MemorySnapshot
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.PowerSample
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.TelemetryInventory
import ai.verispec.core.domain.model.ThermalSample
import ai.verispec.core.domain.model.UnsignedBundle
import ai.verispec.core.domain.model.WorkloadInput
import kotlinx.coroutines.flow.Flow

/**
 * The narrow port every inference runtime sits behind. The domain never learns
 * what a delegate implementation is; it only sees the capability model.
 */
interface InferenceBackend {
    val id: RuntimeId
    suspend fun capabilities(): BackendCapabilities
    suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int): LoadedModel
    suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult
    suspend fun unload(model: LoadedModel)
}

interface ThermalTelemetrySource {
    /** Emits whenever the platform thermal state or headroom changes, plus a periodic tick. */
    fun stream(): Flow<ThermalSample>
    suspend fun halVersion(): String?
}

interface PowerTelemetrySource {
    fun stream(): Flow<PowerSample>
}

/** Everything the precondition gate checks, read on demand. */
/** Clocks and the sensor inventory. Every read is best-effort and never throws. */
interface HardwareTelemetrySource {
    /** Cheap enough to call after every iteration. */
    fun clocks(): ClockReading?
    suspend fun inventory(): TelemetryInventory
}

interface DeviceConditionSource {
    suspend fun read(): DeviceConditions
}

interface MemoryTelemetrySource {
    suspend fun snapshot(): MemorySnapshot
}

interface DeviceProfiler {
    suspend fun profile(): DeviceProfile
}

interface EvidenceSigner {
    /**
     * Signs canonical bytes with a key whose attestation challenge is bound to
     * [challenge] (the manifest hash), so the certificate chain proves which run
     * the key was created for.
     */
    suspend fun sign(canonicalBytes: ByteArray, challenge: Sha256): DetachedSignature
    suspend fun attestationChain(challenge: Sha256): List<EncodedCertificate>
}

/** Third-party verification. If nobody but the producer can verify a bundle, it isn't evidence. */
interface SignatureVerifier {
    fun verify(canonicalBytes: ByteArray, signature: DetachedSignature, leaf: EncodedCertificate): Boolean
}

interface AttestationVerifier {
    data class ChainResult(
        val chainValid: Boolean,
        val hardwareBacked: Boolean,
        val strongBox: Boolean,
        val challenge: Sha256?,
        val issues: List<String>,
    )

    fun verifyChain(chain: List<EncodedCertificate>): ChainResult
}

interface EvidenceRepository {
    suspend fun save(bundle: EvidenceBundle, imported: Boolean = false): BundleId
    fun observeAll(): Flow<List<BundleSummary>>
    suspend fun load(id: BundleId): EvidenceBundle?
    suspend fun export(id: BundleId, format: ExportFormat): ExportedArtifact
    suspend fun delete(id: BundleId)
}

interface ClauseCatalog {
    suspend fun latestVersion(): CatalogVersion
    suspend fun catalog(version: CatalogVersion): List<ClauseDefinition>
}

/**
 * Canonical byte production lives outside the domain (it needs a serializer),
 * but the contract is domain-owned: identical content must yield identical bytes.
 */
interface Canonicalizer {
    /** Canonical manifest bytes, excluding the manifestId field. */
    fun manifestBytes(manifest: RunManifest): ByteArray
    /** Canonical bundle bytes, excluding bundleId, signature and attestation chain. */
    fun bundleBytes(bundle: UnsignedBundle): ByteArray
}

fun interface Hasher {
    fun sha256(bytes: ByteArray): Sha256
}

/**
 * Injected clock so runs never depend on wall-clock implicitly. The monotonic
 * source drives every elapsed-time measurement; wall time is recorded once.
 */
interface Clock {
    fun epochMillis(): Long
    fun monotonicNanos(): Long
}
