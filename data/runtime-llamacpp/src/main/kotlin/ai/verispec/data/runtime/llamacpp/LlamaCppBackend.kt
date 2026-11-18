package ai.verispec.data.runtime.llamacpp

import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.Delegate
import ai.verispec.core.domain.model.InferenceResult
import ai.verispec.core.domain.model.LoadedModel
import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.ModelRef
import ai.verispec.core.domain.model.RuntimeId
import ai.verispec.core.domain.model.WorkloadInput
import ai.verispec.core.domain.port.InferenceBackend
import ai.verispec.data.runtime.api.AdapterSupport
import ai.verispec.data.runtime.api.ModelFiles
import ai.verispec.data.runtime.api.SimpleLoadedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * llama.cpp adapter (GGUF): the format Google will never ship, and the
 * differentiator. Independently absent by design: without the native build the
 * backend reports itself unavailable and the run records the gap.
 */
@Singleton
class LlamaCppBackend @Inject constructor() : InferenceBackend {

    override val id: RuntimeId = RuntimeId.LLAMA_CPP

    private class Handle(
        ref: ModelRef,
        requested: Delegate,
        effective: Delegate,
        version: String?,
        val ctx: Long,
    ) : SimpleLoadedModel(ref, requested, effective, version, ctx)

    override suspend fun capabilities(): BackendCapabilities = withContext(Dispatchers.Default) {
        if (!LlamaNative.ensureLoaded()) {
            return@withContext BackendCapabilities.unavailable(id, LlamaNative.loadError ?: "native library not loaded")
        }
        val info = runCatching { LlamaNative.nativeSystemInfo() }.getOrDefault("")
        val gpu = info.contains("GPU", ignoreCase = true) || info.contains("VULKAN", ignoreCase = true) || info.contains("OPENCL", ignoreCase = true)
        BackendCapabilities(
            runtime = id,
            available = true,
            unavailableReason = null,
            runtimeVersion = runCatching { LlamaNative.nativeVersion() }.getOrNull(),
            supportedFormats = setOf(ModelFormat.GGUF),
            supportedDelegates = if (gpu) setOf(Delegate.CPU, Delegate.GPU) else setOf(Delegate.CPU),
            maxThreads = Runtime.getRuntime().availableProcessors(),
            supportsTextGeneration = true,
            supportsTensorInference = false,
            supportsLogitProjection = true,
            notes = listOf("llama.cpp system info: $info"),
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int, accelerator: String?): LoadedModel = withContext(Dispatchers.IO) {
        check(LlamaNative.ensureLoaded()) { LlamaNative.loadError ?: "native library not loaded" }
        val file = ModelFiles.resolveAndVerify(model)
        val gpuLayers = if (delegate == Delegate.GPU) ALL_GPU_LAYERS else 0
        val ctx = LlamaNative.nativeLoad(file.absolutePath, threadCount, gpuLayers, CONTEXT_TOKENS)
        check(ctx != 0L) { "llama.cpp failed to load ${file.name}" }
        Handle(model, delegate, if (gpuLayers > 0) Delegate.GPU else Delegate.CPU, runCatching { LlamaNative.nativeVersion() }.getOrNull(), ctx)
    }

    override suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult = withContext(Dispatchers.Default) {
        val handle = model as Handle
        val prompt = input.prompt ?: throw IllegalArgumentException("llama.cpp adapter needs a text workload")
        val start = AdapterSupport.nowMicros()
        // A capped generation can end mid-character; decoding replaces the fragment deterministically.
        val output = String(LlamaNative.nativeGenerate(handle.ctx, prompt.toByteArray(Charsets.UTF_8), input.maxNewTokens), Charsets.UTF_8)
        val ttft = LlamaNative.nativeLastFirstTokenMicros(handle.ctx)
        val total = LlamaNative.nativeLastTotalMicros(handle.ctx)
        AdapterSupport.textResult(
            outputText = output,
            startMicros = start,
            firstTokenMicros = start + ttft,
            endMicros = start + total,
            prefillTokens = LlamaNative.nativeLastPrefillTokens(handle.ctx),
            decodeTokens = LlamaNative.nativeLastDecodeTokens(handle.ctx),
            logits = LlamaNative.nativeLastLogits(handle.ctx),
        )
    }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.IO) {
        runCatching { LlamaNative.nativeFree((model as Handle).ctx) }
        Unit
    }

    private companion object {
        const val ALL_GPU_LAYERS = 99
        const val CONTEXT_TOKENS = 2048
    }
}
