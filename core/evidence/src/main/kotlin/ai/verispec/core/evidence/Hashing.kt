package ai.verispec.core.evidence

import ai.verispec.core.domain.model.Sha256
import ai.verispec.core.domain.port.Hasher
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 over bytes, streams and multi-GB model files without loading them into memory. */
object Hashing : Hasher {

    override fun sha256(bytes: ByteArray): Sha256 =
        Sha256.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): Sha256 = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: File): Sha256 = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): Sha256 {
        val hashing = HashingSink.sha256(blackholeSink())
        hashing.buffer().use { sink ->
            input.source().use { source -> sink.writeAll(source) }
        }
        return Sha256(hashing.hash.hex())
    }

    /** Deterministic digest of a float vector: little-endian IEEE-754, in order. */
    fun sha256(floats: FloatArray): Sha256 {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(4)
        for (f in floats) {
            val bits = f.toRawBits()
            buf[0] = (bits and 0xFF).toByte()
            buf[1] = ((bits ushr 8) and 0xFF).toByte()
            buf[2] = ((bits ushr 16) and 0xFF).toByte()
            buf[3] = ((bits ushr 24) and 0xFF).toByte()
            md.update(buf)
        }
        return Sha256.fromBytes(md.digest())
    }
}
