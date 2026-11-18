package ai.verispec.data.persistence

import ai.verispec.core.domain.model.BundleId
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles are files in app-scoped storage, referenced by an index row, never
 * blobs in SQLite. Writes are atomic (temp file + rename) so a crash cannot
 * leave a half-written bundle behind.
 */
@Singleton
class FileBundleStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val directory: File get() = File(context.filesDir, "bundles").also { it.mkdirs() }

    fun fileFor(id: BundleId): File = File(directory, "${id.value}.json")

    suspend fun write(id: BundleId, bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val target = fileFor(id)
        val tmp = File(directory, "${id.value}.json.tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not move bundle into place: $target" }
        target
    }

    suspend fun read(id: BundleId): ByteArray? = withContext(Dispatchers.IO) {
        fileFor(id).takeIf { it.exists() }?.readBytes()
    }

    suspend fun delete(id: BundleId): Boolean = withContext(Dispatchers.IO) { fileFor(id).delete() }
}
