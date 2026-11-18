package ai.verispec.data.runtime.api

import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.InferenceResult
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.Sha256
import java.security.MessageDigest

/**
 * Shared helpers so every adapter measures and digests the same way. Identical
 * measurement code paths for every backend is a neutrality guarantee, not a
 * convenience.
 */
object AdapterSupport {

    /** Monotonic microsecond stamp. */
    fun nowMicros(): Long = System.nanoTime() / 1_000

    fun sha256(bytes: ByteArray): Sha256 = Sha256.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): Sha256 = sha256(text.toByteArray(Charsets.UTF_8))

    /** Little-endian IEEE-754 digest of a float tensor; matches core/evidence Hashing. */
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

    /**
     * Fixed-size projection of an arbitrary-length float output for divergence
     * math. Deterministic bucket-mean pooling: the same output always projects
     * to the same vector, and the projection carries no recoverable content.
     */
    fun project(values: FloatArray, size: Int = PROJECTION_SIZE): FloatArray {
        if (values.isEmpty()) return FloatArray(size)
        if (values.size <= size) return values.copyOf(size)
        val out = FloatArray(size)
        val counts = IntArray(size)
        for (i in values.indices) {
            val bucket = (i.toLong() * size / values.size).toInt()
            out[bucket] += values[i]
            counts[bucket]++
        }
        for (b in 0 until size) if (counts[b] > 0) out[b] /= counts[b]
        return out
    }

    /** Builds a text-generation result from timing marks captured around the runtime call. */
    fun textResult(
        outputText: String,
        startMicros: Long,
        firstTokenMicros: Long,
        endMicros: Long,
        prefillTokens: Int,
        decodeTokens: Int,
        logits: FloatArray? = null,
    ): InferenceResult = InferenceResult(
        outputSha256 = sha256(outputText),
        logitProjection = logits?.let { project(it) },
        timeToFirstTokenMicros = (firstTokenMicros - startMicros).coerceAtLeast(0),
        totalMicros = (endMicros - startMicros).coerceAtLeast(0),
        prefillTokens = prefillTokens,
        decodeTokens = decodeTokens,
    )

    /**
     * Builds a tensor-inference result. The whole forward pass counts as "first token"
     * and as one decode unit, so decodeTokensPerSecond reads as inferences per second.
     */
    fun tensorResult(output: FloatArray, startMicros: Long, endMicros: Long, inputElements: Int): InferenceResult =
        InferenceResult(
            outputSha256 = sha256(output),
            logitProjection = project(output),
            timeToFirstTokenMicros = (endMicros - startMicros).coerceAtLeast(0),
            totalMicros = (endMicros - startMicros).coerceAtLeast(0),
            prefillTokens = inputElements,
            decodeTokens = 1,
        )

    /** Rough whitespace tokenizer used only when a runtime does not expose token counts. */
    fun approximateTokenCount(text: String): Int = text.split(WHITESPACE).count { it.isNotBlank() }

    private val WHITESPACE = Regex("\\s+")
    const val PROJECTION_SIZE = 64
}

/** Default handle implementation adapters can extend or use directly. */
open class SimpleLoadedModel(
    override val ref: ModelRef,
    override val requestedDelegate: Delegate,
    override val effectiveDelegate: Delegate,
    override val runtimeVersionObserved: String?,
    val nativeHandle: Any?,
) : LoadedModel
