package ai.verispec.app.runner

import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.PreconditionState
import ai.verispec.core.domain.model.RunProgress
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Long-running foreground worker, one per run. Survives process death,
 * screen-off and Doze. Holds only a ManifestId.
 *
 * Wake lock policy (documented because it affects our own power measurements):
 * a PARTIAL_WAKE_LOCK is held for the duration of doWork() so the CPU keeps
 * running with the screen off. It is released in `finally`. The screen is not
 * kept on; power samples therefore reflect CPU/NPU load without display draw.
 */
@HiltWorker
class RunWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: RunPipeline,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MANIFEST_ID)?.let { ManifestId(it) } ?: return Result.failure()
        setForeground(foregroundInfo("Preparing run ${id.short}…", 0))

        val powerManager = applicationContext.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "verispec:run:${id.short}")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(MAX_WAKE_LOCK_MILLIS)

        return try {
            when (val outcome = pipeline.run(id, ::report)) {
                is RunPipeline.Outcome.Completed -> Result.success(workDataOf(KEY_BUNDLE_ID to outcome.bundleId))
                is RunPipeline.Outcome.Failed -> Result.failure(workDataOf(KEY_FAILURE to outcome.reason))
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private suspend fun report(progress: RunProgress) {
        when (progress) {
            is RunProgress.AwaitingPreconditions -> {
                val text = gateText(progress.state)
                setProgress(workDataOf(KEY_STAGE to STAGE_PRECONDITIONS, KEY_FRACTION to 0f, KEY_DETAIL to text))
                // The screen is usually meant to be off here, so the notification is how the operator follows the wait.
                if (text != lastGateText) {
                    lastGateText = text
                    setForeground(foregroundInfo(text, 0))
                }
            }
            is RunProgress.Started -> setProgress(workDataOf(KEY_STAGE to STAGE_STARTED, KEY_FRACTION to 0f))
            is RunProgress.ModelLoaded -> setProgress(workDataOf(KEY_STAGE to STAGE_LOADED, KEY_FRACTION to 0f))
            is RunProgress.Warmup -> setProgress(
                workDataOf(KEY_STAGE to STAGE_WARMUP, KEY_FRACTION to 0f, KEY_ITERATION to progress.index, KEY_TOTAL to progress.total),
            )
            is RunProgress.Iteration -> {
                setProgress(
                    workDataOf(
                        KEY_STAGE to STAGE_ITERATING,
                        KEY_FRACTION to progress.fraction,
                        KEY_ITERATION to progress.completed,
                        KEY_TOTAL to progress.total,
                        KEY_THERMAL to progress.liveThermal.name,
                        KEY_HEADROOM to (progress.liveHeadroom ?: -1f),
                        KEY_TPS to progress.record.decodeTokensPerSecond,
                    ),
                )
                if (progress.completed % NOTIFY_EVERY == 0 || progress.completed == progress.total) {
                    setForeground(
                        foregroundInfo(
                            "Iteration ${progress.completed}/${progress.total} · ${"%.1f".format(progress.record.decodeTokensPerSecond)}/s ·${progress.liveThermal}",
                            (progress.fraction * 100).toInt(),
                        ),
                    )
                }
            }
            is RunProgress.Completed -> setProgress(workDataOf(KEY_STAGE to STAGE_SIGNING, KEY_FRACTION to 1f))
            is RunProgress.Failed -> setProgress(workDataOf(KEY_STAGE to STAGE_FAILED, KEY_FAILURE to progress.failure.message))
        }
    }

    private var lastGateText: String? = null

    private fun gateText(state: PreconditionState): String = when (state) {
        is PreconditionState.Waiting -> if (state.unmet.isEmpty()) {
            "Conditions met; holding ${state.heldMillis / 1000} s before starting"
        } else {
            "Waiting: " + state.unmet.joinToString("; ") { it.description }
        }
        is PreconditionState.Satisfied -> "Conditions met; starting"
        is PreconditionState.TimedOut -> "Preconditions not met"
    }

    private fun foregroundInfo(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VeriSpec runs", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sustained-load evidence runs in progress"
            },
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("VeriSpec-AI evidence run")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MANIFEST_ID = "manifestId"
        const val KEY_BUNDLE_ID = "bundleId"
        const val KEY_FAILURE = "failure"
        const val KEY_STAGE = "stage"
        const val KEY_FRACTION = "fraction"
        const val KEY_ITERATION = "iteration"
        const val KEY_TOTAL = "total"
        const val KEY_THERMAL = "thermal"
        const val KEY_HEADROOM = "headroom"
        const val KEY_TPS = "tps"
        const val KEY_DETAIL = "detail"

        const val STAGE_PRECONDITIONS = "waiting for preconditions"
        const val STAGE_STARTED = "started"
        const val STAGE_LOADED = "loaded"
        const val STAGE_WARMUP = "warmup"
        const val STAGE_ITERATING = "iterating"
        const val STAGE_SIGNING = "signing"
        const val STAGE_FAILED = "failed"

        const val CHANNEL_ID = "verispec.runs"
        const val NOTIFICATION_ID = 0x5EC1
        const val NOTIFY_EVERY = 5
        /** Hard ceiling so a stuck backend cannot pin the CPU forever. */
        const val MAX_WAKE_LOCK_MILLIS = 4L * 60 * 60 * 1000
    }
}
