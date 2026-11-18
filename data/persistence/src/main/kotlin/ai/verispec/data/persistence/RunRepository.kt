package ai.verispec.data.persistence

import ai.verispec.core.domain.model.IterationRecord
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.OutputDigest
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.core.domain.port.Clock
import ai.verispec.core.evidence.BundleCodec
import ai.verispec.data.persistence.db.IterationDao
import ai.verispec.data.persistence.db.IterationEntity
import ai.verispec.data.persistence.db.RunDao
import ai.verispec.data.persistence.db.RunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Run lifecycle state on this device. The worker re-resolves everything from here. */
data class RunRecord(
    val manifest: RunManifest,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val failureMessage: String?,
    val bundleId: String?,
    val workRequestId: String?,
)

interface RunRepository {
    suspend fun enqueue(manifest: RunManifest, workRequestId: String?): ManifestId
    suspend fun get(id: ManifestId): RunRecord?
    fun observe(id: ManifestId): Flow<RunRecord?>
    fun observeAll(): Flow<List<RunRecord>>
    suspend fun markRunning(id: ManifestId)
    suspend fun markCompleted(id: ManifestId, bundleId: String)
    suspend fun markFailed(id: ManifestId, message: String)
    suspend fun markCancelled(id: ManifestId)
    /** Flushed after every iteration; idempotent on restart. */
    suspend fun appendIteration(id: ManifestId, record: IterationRecord, digest: OutputDigest)
    suspend fun iterations(id: ManifestId): List<Pair<IterationRecord, OutputDigest>>
    fun observeIterations(id: ManifestId): Flow<List<Pair<IterationRecord, OutputDigest>>>
    suspend fun delete(id: ManifestId)
}

@Singleton
class RoomRunRepository @Inject constructor(
    private val runs: RunDao,
    private val iterations: IterationDao,
    private val clock: Clock,
) : RunRepository {

    override suspend fun enqueue(manifest: RunManifest, workRequestId: String?): ManifestId {
        val now = clock.epochMillis()
        val existing = runs.get(manifest.manifestId.value)
        runs.upsert(
            RunEntity(
                manifestId = manifest.manifestId.value,
                manifestJson = BundleCodec.encodeManifest(manifest).toString(Charsets.UTF_8),
                label = manifest.label,
                status = RunEntity.STATUS_PENDING,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
                failureMessage = null,
                bundleId = null,
                workRequestId = workRequestId,
            ),
        )
        // A re-enqueued manifest starts from scratch; partial iterations belong to the previous attempt.
        iterations.deleteForRun(manifest.manifestId.value)
        return manifest.manifestId
    }

    override suspend fun get(id: ManifestId): RunRecord? = runs.get(id.value)?.toRecord()

    override fun observe(id: ManifestId): Flow<RunRecord?> = runs.observe(id.value).map { it?.toRecord() }

    override fun observeAll(): Flow<List<RunRecord>> = runs.observeAll().map { list -> list.map { it.toRecord() } }

    override suspend fun markRunning(id: ManifestId) =
        runs.updateStatus(id.value, RunEntity.STATUS_RUNNING, null, null, null, clock.epochMillis())

    override suspend fun markCompleted(id: ManifestId, bundleId: String) =
        runs.updateStatus(id.value, RunEntity.STATUS_COMPLETED, null, bundleId, null, clock.epochMillis())

    override suspend fun markFailed(id: ManifestId, message: String) =
        runs.updateStatus(id.value, RunEntity.STATUS_FAILED, message, null, null, clock.epochMillis())

    override suspend fun markCancelled(id: ManifestId) =
        runs.updateStatus(id.value, RunEntity.STATUS_CANCELLED, null, null, null, clock.epochMillis())

    override suspend fun appendIteration(id: ManifestId, record: IterationRecord, digest: OutputDigest) {
        iterations.appendBatch(listOf(record.toEntity(id, digest)))
    }

    override suspend fun iterations(id: ManifestId): List<Pair<IterationRecord, OutputDigest>> =
        iterations.forRun(id.value).map { it.toDomain() }

    override fun observeIterations(id: ManifestId): Flow<List<Pair<IterationRecord, OutputDigest>>> =
        iterations.observeForRun(id.value).map { rows -> rows.map { it.toDomain() } }

    override suspend fun delete(id: ManifestId) {
        iterations.deleteForRun(id.value)
        runs.delete(id.value)
    }

    private fun RunEntity.toRecord() = RunRecord(
        manifest = BundleCodec.decodeManifest(manifestJson),
        status = status,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        failureMessage = failureMessage,
        bundleId = bundleId,
        workRequestId = workRequestId,
    )

    private fun IterationRecord.toEntity(id: ManifestId, digest: OutputDigest) = IterationEntity(
        manifestId = id.value,
        iterationIndex = index,
        elapsedMillis = elapsedMillis,
        timeToFirstTokenMicros = timeToFirstTokenMicros,
        totalMicros = totalMicros,
        prefillTokens = prefillTokens,
        decodeTokens = decodeTokens,
        decodeTokensPerSecond = decodeTokensPerSecond,
        prefillTokensPerSecond = prefillTokensPerSecond,
        thermalStatusAtStart = thermalStatusAtStart.name,
        thermalStatusAtEnd = thermalStatusAtEnd.name,
        wasThrottled = wasThrottled,
        outputSha256 = digest.sha256.hex,
        logitProjection = digest.logitProjection?.let(::floatsToBytes),
    )

    private fun IterationEntity.toDomain(): Pair<IterationRecord, OutputDigest> =
        IterationRecord(
            index = iterationIndex,
            elapsedMillis = elapsedMillis,
            timeToFirstTokenMicros = timeToFirstTokenMicros,
            totalMicros = totalMicros,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens,
            decodeTokensPerSecond = decodeTokensPerSecond,
            prefillTokensPerSecond = prefillTokensPerSecond,
            thermalStatusAtStart = ThermalStatus.valueOf(thermalStatusAtStart),
            thermalStatusAtEnd = ThermalStatus.valueOf(thermalStatusAtEnd),
            wasThrottled = wasThrottled,
        ) to OutputDigest(iterationIndex, Sha256(outputSha256), logitProjection?.let(::bytesToFloats))

    companion object {
        fun floatsToBytes(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            floats.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buf.getFloat() }
        }
    }
}
