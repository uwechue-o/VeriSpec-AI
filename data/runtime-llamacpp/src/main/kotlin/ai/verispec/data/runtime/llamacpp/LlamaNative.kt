package ai.verispec.data.runtime.llamacpp

/**
 * JNI surface to the llama.cpp bridge in src/main/cpp/llama_jni.cpp.
 * Symbol names follow the JNI convention for this exact package and class:
 * `Java_ai_verispec_data_runtime_llamacpp_LlamaNative_<method>`.
 */
object LlamaNative {

    @Volatile private var loaded: Boolean? = null
    @Volatile var loadError: String? = null
        private set

    /** Loads libverispec_llama.so once. False (never a crash) when the native build is absent. */
    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = if (!BuildConfig.NATIVE_LLAMA_ENABLED) {
                loadError = "native llama.cpp build disabled (verispec.nativeLlama=false)"
                false
            } else {
                try {
                    System.loadLibrary("verispec_llama")
                    nativeInit()
                } catch (t: Throwable) {
                    loadError = "${t::class.simpleName}: ${t.message}"
                    false
                }
            }
            loaded = ok
            return ok
        }
    }

    external fun nativeInit(): Boolean
    external fun nativeVersion(): String
    external fun nativeSystemInfo(): String

    /** Returns an opaque context handle, or 0 on failure. */
    external fun nativeLoad(path: String, threads: Int, gpuLayers: Int, contextTokens: Int): Long

    /**
     * Greedy generation over UTF-8 bytes (not modified UTF-8, see llama_jni.cpp).
     * Returns the generated text's bytes; timing/counts are read back with the accessors.
     */
    external fun nativeGenerate(ctx: Long, prompt: ByteArray, maxTokens: Int): ByteArray
    external fun nativeLastPrefillTokens(ctx: Long): Int
    external fun nativeLastDecodeTokens(ctx: Long): Int
    external fun nativeLastFirstTokenMicros(ctx: Long): Long
    external fun nativeLastTotalMicros(ctx: Long): Long
    /** Logits of the final decode step, or null. */
    external fun nativeLastLogits(ctx: Long): FloatArray?
    external fun nativeFree(ctx: Long)
}
