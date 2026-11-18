package ai.verispec.app.model

import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.Sha256
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a user-picked model (content://) into app-scoped storage, hashing it
 * on the way so the manifest can name the exact bytes. Adapters re-verify the
 * hash at load time; this is the first of two checks, not the only one.
 */
@Singleton
class ModelImporter @Inject constructor(@ApplicationContext private val context: Context) {

    data class Imported(val file: File, val sha256: Sha256, val sizeBytes: Long, val displayName: String, val guessedFormat: ModelFormat?) {
        val fileUri: String get() = file.toURI().toString()
    }

    val directory: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    suspend fun import(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val target = File(directory, name)
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
        val hashing = HashingSink.sha256(target.sink())
        var size = 0L
        input.source().use { source ->
            hashing.buffer().use { sink ->
                size = sink.writeAll(source)
            }
        }
        Imported(target, Sha256(hashing.hash.hex()), size, name, guessFormat(name))
    }

    /**
     * Downloads a starter model into app storage, or reuses a previous copy whose
     * bytes still hash to the pinned value. A download that does not match the
     * pin is deleted and reported, never used.
     */
    suspend fun fetchStarter(model: StarterModel): Imported = withContext(Dispatchers.IO) {
        val target = File(directory, model.fileName)
        if (target.isFile && sha256Of(target) == model.sha256) {
            return@withContext Imported(target, Sha256(model.sha256), target.length(), model.fileName, model.format)
        }
        val partial = File(directory, model.fileName + ".part")
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code from ${model.url}")
            val hashing = HashingSink.sha256(partial.sink())
            var size = 0L
            connection.inputStream.source().use { source ->
                hashing.buffer().use { sink -> size = sink.writeAll(source) }
            }
            val hex = hashing.hash.hex()
            if (hex != model.sha256) {
                partial.delete()
                error("Downloaded bytes hash to $hex, expected ${model.sha256}")
            }
            target.delete()
            if (!partial.renameTo(target)) error("Could not move download into place")
            Imported(target, Sha256(hex), size, model.fileName, model.format)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun sha256Of(file: File): String {
        val hashing = HashingSink.sha256(blackholeSink())
        file.source().use { source -> hashing.buffer().use { it.writeAll(source) } }
        return hashing.hash.hex()
    }

    fun listImported(): List<File> = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return sanitize(it) }
            }
        }
        return sanitize(uri.lastPathSegment ?: "model.bin")
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        fun guessFormat(name: String): ModelFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "gguf" -> ModelFormat.GGUF
            "tflite" -> ModelFormat.TFLITE
            "task", "litertlm" -> ModelFormat.LITERT
            "pte" -> ModelFormat.EXECUTORCH
            "onnx", "ort" -> ModelFormat.ONNX
            else -> null
        }
    }
}
