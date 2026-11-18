package ai.verispec.data.runtime.api.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Read-only access to Android system properties via `getprop`, which is the
 * supported path for apps (android.os.SystemProperties is hidden API). Returns
 * null on any failure, including when running on a plain JVM.
 */
object SystemProps {

    private val cache = HashMap<String, String?>()

    fun get(key: String): String? = synchronized(cache) {
        cache.getOrPut(key) { read(key) }
    }

    fun firstNonBlank(vararg keys: String): String? =
        keys.asSequence().mapNotNull { get(it) }.firstOrNull { it.isNotBlank() }

    /** True when a vendor library exists, e.g. QNN's HTP backend. */
    fun libraryExists(vararg names: String): Boolean =
        LIB_DIRS.any { dir -> names.any { File(dir, it).exists() } }

    private fun read(key: String): String? = try {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        text.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private val LIB_DIRS = listOf("/vendor/lib64", "/vendor/lib64/egl", "/system/lib64", "/odm/lib64", "/system_ext/lib64")
}
