package ai.verispec.data.persistence

import ai.verispec.core.domain.model.BundleId
import ai.verispec.core.domain.model.BundleSummary
import ai.verispec.core.domain.model.CoverageStatus
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.EvidenceBundle
import ai.verispec.core.domain.model.ExportFormat
import ai.verispec.core.domain.model.ExportedArtifact
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.SignatureStatus
import ai.verispec.core.domain.port.EvidenceRepository
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.data.persistence.db.BundleIndexDao
import ai.verispec.data.persistence.db.BundleIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomEvidenceRepository @Inject constructor(
    private val index: BundleIndexDao,
    private val files: FileBundleStore,
) : EvidenceRepository {

    override suspend fun save(bundle: EvidenceBundle, imported: Boolean): BundleId {
        val bytes = BundleCodec.encode(bundle)
        val file = files.write(bundle.bundleId, bytes)
        index.upsert(toIndex(bundle, file.absolutePath, imported, SignatureStatus.UNVERIFIED))
        return bundle.bundleId
    }

    /** Records the outcome of a verification so the list can show it without re-verifying. */
    suspend fun markSignatureStatus(id: BundleId, status: SignatureStatus) {
        val row = index.get(id.value) ?: return
        index.upsert(row.copy(signatureStatus = status.name))
    }

    override fun observeAll(): Flow<List<BundleSummary>> = index.observeAll().map { rows -> rows.map { it.toSummary() } }

    override suspend fun load(id: BundleId): EvidenceBundle? = withContext(Dispatchers.Default) {
        files.read(id)?.let { BundleCodec.decode(it) }
    }

    override suspend fun export(id: BundleId, format: ExportFormat): ExportedArtifact {
        val bytes = files.read(id) ?: throw NoSuchElementException("No bundle $id")
        return when (format) {
            ExportFormat.JSON -> ExportedArtifact(
                format = ExportFormat.JSON,
                fileName = "verispec-bundle-${id.short}.json",
                mimeType = "application/json",
                bytes = bytes,
            )
            // PDF rendering (PDFBox) is JVM-only by design: tools/report is headless.
            ExportFormat.PDF -> throw UnsupportedOperationException(
                "PDF export runs on the host: `verispec report --bundle <file>`. Export JSON from the device.",
            )
        }
    }

    override suspend fun delete(id: BundleId) {
        index.delete(id.value)
        files.delete(id)
    }

    private fun BundleIndexEntity.toSummary() = BundleSummary(
        bundleId = BundleId(bundleId),
        manifestId = ManifestId(manifestId),
        label = label,
        deviceFingerprint = deviceFingerprint,
        socModel = socModel,
        runtime = RuntimeId.valueOf(runtime),
        delegate = Delegate.valueOf(delegate),
        quantization = quantization,
        createdAtEpochMillis = createdAtEpochMillis,
        iterationCount = iterationCount,
        meanDecodeTokensPerSecond = meanDecodeTokensPerSecond,
        throttledIterations = throttledIterations,
        signatureStatus = SignatureStatus.valueOf(signatureStatus),
        coveredClauses = coveredClauses,
        totalClauses = totalClauses,
        imported = imported,
    )

    companion object {
        fun toIndex(bundle: EvidenceBundle, path: String, imported: Boolean, status: SignatureStatus) = BundleIndexEntity(
            bundleId = bundle.bundleId.value,
            manifestId = bundle.manifest.manifestId.value,
            label = bundle.manifest.label,
            filePath = path,
            createdAtEpochMillis = bundle.signature.signedAtEpochMillis,
            deviceFingerprint = bundle.device.fingerprint,
            socModel = bundle.device.soc.model,
            runtime = bundle.manifest.backend.runtime.name,
            delegate = bundle.provenance.delegateActuallyUsed.name,
            quantization = bundle.manifest.model.quantization.scheme,
            iterationCount = bundle.observations.iterations.size,
            meanDecodeTokensPerSecond = bundle.observations.iterations.map { it.decodeTokensPerSecond }.average().takeUnless { it.isNaN() } ?: 0.0,
            throttledIterations = bundle.observations.iterations.count { it.wasThrottled },
            signatureStatus = status.name,
            coveredClauses = bundle.clauseCoverage.count { it.status == CoverageStatus.SATISFIED },
            totalClauses = bundle.clauseCoverage.size,
            imported = imported,
        )
    }
}
