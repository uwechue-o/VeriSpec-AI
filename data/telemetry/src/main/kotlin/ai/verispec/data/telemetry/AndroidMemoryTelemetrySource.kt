package ai.verispec.data.telemetry

import ai.verispec.core.domain.model.MemorySnapshot
import ai.verispec.core.domain.port.MemoryTelemetrySource
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Process PSS / native heap / RSS and system available memory. */
@Singleton
class AndroidMemoryTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MemoryTelemetrySource {

    private val activityManager: ActivityManager by lazy { context.getSystemService(ActivityManager::class.java) }

    override suspend fun snapshot(): MemorySnapshot = withContext(Dispatchers.IO) {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val system = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        MemorySnapshot(
            pssKb = info.totalPss.toLong(),
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            rssKb = readRssKb(),
            availableSystemKb = system.availMem / 1024,
        )
    }

    private fun readRssKb(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toLongOrNull()
        }
    }.getOrNull()
}
