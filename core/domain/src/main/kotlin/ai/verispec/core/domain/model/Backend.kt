package ai.verispec.core.domain.model

/**
 * Explicit capability model. Discovered at startup, never assumed at compile
 * time: a device without a QNN delegate degrades to a recorded gap, not a crash.
 */
data class BackendCapabilities(
    val runtime: RuntimeId,
    /** False when the adapter is compiled in but its native runtime is absent on this device. */
    val available: Boolean,
    val unavailableReason: String?,
    val runtimeVersion: String?,
    val supportedFormats: Set<ModelFormat>,
    val supportedDelegates: Set<Delegate>,
    val maxThreads: Int,
    val supportsTextGeneration: Boolean,
    val supportsTensorInference: Boolean,
    val supportsLogitProjection: Boolean,
    val notes: List<String> = emptyList(),
) {
    companion object {
        fun unavailable(runtime: RuntimeId, reason: String) = BackendCapabilities(
            runtime = runtime,
            available = false,
            unavailableReason = reason,
            runtimeVersion = null,
            supportedFormats = emptySet(),
            supportedDelegates = emptySet(),
            maxThreads = 0,
            supportsTextGeneration = false,
            supportsTensorInference = false,
            supportsLogitProjection = false,
        )
    }
}

/** Opaque handle to a model the backend has loaded. Adapters subclass it. */
interface LoadedModel {
    val ref: ModelRef
    val requestedDelegate: Delegate
    /** The delegate that actually ended up executing (fallbacks are recorded, not hidden). */
    val effectiveDelegate: Delegate
    val runtimeVersionObserved: String?
}

/**
 * Result of a single inference. Raw output never leaves the adapter: it is
 * hashed and optionally projected into a fixed-size numeric vector.
 */
class InferenceResult(
    val outputSha256: Sha256,
    val logitProjection: FloatArray?,
    val timeToFirstTokenMicros: Long,
    val totalMicros: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
) {
    /**
     * Decode tokens over the decode phase. A single-pass result (tensor inference:
     * first output == last output) has no separate decode phase, so its rate is
     * over the whole call; with one decode unit per pass that is passes per second.
     */
    val decodeTokensPerSecond: Double
        get() {
            val decodeMicros = if (totalMicros > timeToFirstTokenMicros) totalMicros - timeToFirstTokenMicros else totalMicros
            return decodeTokens.toDouble() / (decodeMicros.coerceAtLeast(1) / 1_000_000.0)
        }

    val prefillTokensPerSecond: Double
        get() = prefillTokens.toDouble() / (timeToFirstTokenMicros.coerceAtLeast(1) / 1_000_000.0)

    override fun toString(): String =
        "InferenceResult(out=${outputSha256.short}, ttft=${timeToFirstTokenMicros}us, total=${totalMicros}us, " +
            "prefill=$prefillTokens, decode=$decodeTokens)"
}
