package ai.verispec.app.runner

import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.ThermalStatus
import ai.verispec.data.persistence.RunRepository
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Live view of a worker's progress, decoded from WorkManager progress data. */
data class LiveRunStatus(
    val state: WorkInfo.State?,
    val stage: String?,
    /** Human-readable detail for the stage, e.g. which preconditions are still unmet. */
    val detail: String?,
    val fraction: Float,
    val iteration: Int,
    val total: Int,
    val thermal: ThermalStatus,
    val headroom: Float?,
    val tokensPerSecond: Double,
    val failure: String?,
    val bundleId: String?,
)

/** Enqueues one unique foreground worker per manifest and exposes its progress. */
@Singleton
class RunScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runs: RunRepository,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    suspend fun schedule(manifest: RunManifest): ManifestId {
        val request = OneTimeWorkRequestBuilder<RunWorker>()
            .setInputData(workDataOf(RunWorker.KEY_MANIFEST_ID to manifest.manifestId.value))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        runs.enqueue(manifest, request.id.toString())
        workManager.enqueueUniqueWork(uniqueName(manifest.manifestId), ExistingWorkPolicy.REPLACE, request)
        return manifest.manifestId
    }

    fun cancel(id: ManifestId) {
        workManager.cancelUniqueWork(uniqueName(id))
    }

    fun observe(id: ManifestId): Flow<LiveRunStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(id)).map { infos ->
            val info = infos.firstOrNull()
            val p = info?.progress
            val out = info?.outputData
            LiveRunStatus(
                state = info?.state,
                stage = p?.getString(RunWorker.KEY_STAGE),
                detail = p?.getString(RunWorker.KEY_DETAIL),
                fraction = p?.getFloat(RunWorker.KEY_FRACTION, 0f) ?: 0f,
                iteration = p?.getInt(RunWorker.KEY_ITERATION, 0) ?: 0,
                total = p?.getInt(RunWorker.KEY_TOTAL, 0) ?: 0,
                thermal = p?.getString(RunWorker.KEY_THERMAL)?.let { runCatching { ThermalStatus.valueOf(it) }.getOrNull() } ?: ThermalStatus.UNKNOWN,
                headroom = p?.getFloat(RunWorker.KEY_HEADROOM, -1f)?.takeIf { it >= 0f },
                tokensPerSecond = p?.getDouble(RunWorker.KEY_TPS, 0.0) ?: 0.0,
                failure = out?.getString(RunWorker.KEY_FAILURE) ?: p?.getString(RunWorker.KEY_FAILURE),
                bundleId = out?.getString(RunWorker.KEY_BUNDLE_ID),
            )
        }

    private fun uniqueName(id: ManifestId) = "verispec-run-${id.value}"

    companion object {
        const val TAG = "verispec-run"
    }
}
