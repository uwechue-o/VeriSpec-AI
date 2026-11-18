package ai.verispec.data.persistence.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A run as scheduled/executed on this device. The manifest JSON is canonical bytes. */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val manifestId: String,
    val manifestJson: String,
    val label: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val failureMessage: String?,
    val bundleId: String?,
    val workRequestId: String?,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/**
 * Partial iteration records, flushed after every iteration. A crash at
 * iteration 47 must not lose 46 iterations.
 */
@Entity(
    tableName = "iterations",
    primaryKeys = ["manifestId", "iterationIndex"],
    indices = [Index("manifestId")],
)
data class IterationEntity(
    val manifestId: String,
    val iterationIndex: Int,
    val elapsedMillis: Long,
    val timeToFirstTokenMicros: Long,
    val totalMicros: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val decodeTokensPerSecond: Double,
    val prefillTokensPerSecond: Double,
    val thermalStatusAtStart: String,
    val thermalStatusAtEnd: String,
    val wasThrottled: Boolean,
    val outputSha256: String,
    /** Little-endian IEEE-754 floats, or null when projection is disabled. */
    val logitProjection: ByteArray?,
)

/** Index row for a bundle stored as a file. Never the bundle itself. */
@Entity(tableName = "bundle_index", indices = [Index("manifestId"), Index("createdAtEpochMillis")])
data class BundleIndexEntity(
    @PrimaryKey val bundleId: String,
    val manifestId: String,
    val label: String,
    val filePath: String,
    val createdAtEpochMillis: Long,
    val deviceFingerprint: String,
    val socModel: String,
    val runtime: String,
    val delegate: String,
    val quantization: String,
    val iterationCount: Int,
    val meanDecodeTokensPerSecond: Double,
    val throttledIterations: Int,
    val signatureStatus: String,
    val coveredClauses: Int,
    val totalClauses: Int,
    val imported: Boolean,
)
