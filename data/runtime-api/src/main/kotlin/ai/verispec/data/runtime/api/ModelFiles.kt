package ai.verispec.data.runtime.api

import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.Sha256
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Resolves a manifest's model reference to a local file and proves that the
 * bytes on disk are the bytes the manifest names. An adapter must never load a
 * model whose hash it has not checked: provenance would be fiction otherwise.
 */
object ModelFiles {

    class ModelMismatchException(message: String) : IllegalStateException(message)

    fun resolve(model: ModelRef): File {
        val uri = model.sourceUri ?: throw ModelMismatchException("Manifest has no sourceUri for model ${model.sha256.short}")
        val file = when {
            uri.startsWith("file:") -> File(URI(uri))
            uri.startsWith("/") -> File(uri)
            else -> throw ModelMismatchException("Unsupported model URI scheme: $uri (copy content:// models into app storage first)")
        }
        if (!file.isFile) throw ModelMismatchException("Model file not found: $file")
        return file
    }

    /** Streams the file through SHA-256; multi-GB models never sit in memory. */
    fun sha256(file: File): Sha256 {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Sha256.fromBytes(md.digest())
    }

    fun resolveAndVerify(model: ModelRef): File {
        val file = resolve(model)
        val actual = sha256(file)
        if (actual != model.sha256) {
            throw ModelMismatchException(
                "Model hash mismatch for $file: manifest says ${model.sha256.short}…, file is ${actual.short}…",
            )
        }
        return file
    }
}
