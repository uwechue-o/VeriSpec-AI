package ai.verispec.data.persistence.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Upsert suspend fun upsert(run: RunEntity)

    @Query("SELECT * FROM runs WHERE manifestId = :manifestId") suspend fun get(manifestId: String): RunEntity?

    @Query("SELECT * FROM runs ORDER BY createdAtEpochMillis DESC") fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE manifestId = :manifestId") fun observe(manifestId: String): Flow<RunEntity?>

    @Query(
        "UPDATE runs SET status = :status, failureMessage = :failure, bundleId = :bundleId, " +
            "workRequestId = COALESCE(:workRequestId, workRequestId), updatedAtEpochMillis = :now WHERE manifestId = :manifestId",
    )
    suspend fun updateStatus(
        manifestId: String,
        status: String,
        failure: String?,
        bundleId: String?,
        workRequestId: String?,
        now: Long,
    )

    @Query("DELETE FROM runs WHERE manifestId = :manifestId") suspend fun delete(manifestId: String)
}

@Dao
interface IterationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(rows: List<IterationEntity>)

    @Transaction
    suspend fun appendBatch(rows: List<IterationEntity>) = insertAll(rows)

    @Query("SELECT * FROM iterations WHERE manifestId = :manifestId ORDER BY iterationIndex ASC")
    suspend fun forRun(manifestId: String): List<IterationEntity>

    @Query("SELECT * FROM iterations WHERE manifestId = :manifestId ORDER BY iterationIndex ASC")
    fun observeForRun(manifestId: String): Flow<List<IterationEntity>>

    @Query("SELECT COUNT(*) FROM iterations WHERE manifestId = :manifestId")
    suspend fun count(manifestId: String): Int

    @Query("DELETE FROM iterations WHERE manifestId = :manifestId") suspend fun deleteForRun(manifestId: String)
}

@Dao
interface BundleIndexDao {
    @Upsert suspend fun upsert(row: BundleIndexEntity)

    @Query("SELECT * FROM bundle_index WHERE bundleId = :bundleId") suspend fun get(bundleId: String): BundleIndexEntity?

    @Query("SELECT * FROM bundle_index ORDER BY createdAtEpochMillis DESC") fun observeAll(): Flow<List<BundleIndexEntity>>

    @Query("SELECT * FROM bundle_index WHERE manifestId = :manifestId ORDER BY createdAtEpochMillis DESC")
    suspend fun forManifest(manifestId: String): List<BundleIndexEntity>

    @Query("DELETE FROM bundle_index WHERE bundleId = :bundleId") suspend fun delete(bundleId: String)
}
