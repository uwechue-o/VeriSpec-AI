package ai.verispec.data.runtime.mediapipe

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
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe Tasks GenAI (LLM Inference) adapter: text generation over LiteRT
 * task bundles. Greedy decoding is forced (topK=1, temperature=0) because the
 * manifest validator requires it; the seed is passed through for completeness.
 */
@Singleton
class MediaPipeBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : InferenceBackend {

    override val id: RuntimeId = RuntimeId.MEDIAPIPE

    private class Handle(
        ref: ModelRef,
        requested: Delegate,
        effective: Delegate,
        val llm: LlmInference,
        val seed: Int,
    ) : SimpleLoadedModel(ref, requested, effective, RUNTIME_VERSION, llm)

    override suspend fun capabilities(): BackendCapabilities = withContext(Dispatchers.Default) {
        val loadable = runCatching { Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference"); true }.getOrDefault(false)
        if (!loadable) return@withContext BackendCapabilities.unavailable(id, "MediaPipe GenAI classes missing")
        BackendCapabilities(
            runtime = id,
            available = true,
            unavailableReason = null,
            runtimeVersion = RUNTIME_VERSION,
            supportedFormats = setOf(ModelFormat.LITERT, ModelFormat.TFLITE),
            supportedDelegates = setOf(Delegate.CPU, Delegate.GPU),
            maxThreads = Runtime.getRuntime().availableProcessors(),
            supportsTextGeneration = true,
            supportsTensorInference = false,
            supportsLogitProjection = false,
            notes = listOf("Thread count is managed by the runtime; the manifest value is recorded but not applied"),
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int): LoadedModel = withContext(Dispatchers.IO) {
        val file = ModelFiles.resolveAndVerify(model)
        val backend = if (delegate == Delegate.GPU) LlmInference.Backend.GPU else LlmInference.Backend.CPU
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOTAL_TOKENS)
            .setPreferredBackend(backend)
            .build()
        val llm = LlmInference.createFromOptions(context, options)
        Handle(model, delegate, if (backend == LlmInference.Backend.GPU) Delegate.GPU else Delegate.CPU, llm, 0)
    }

    override suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult = withContext(Dispatchers.Default) {
        val handle = model as Handle
        val prompt = input.prompt ?: throw IllegalArgumentException("MediaPipe adapter needs a text workload")

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(1)
            .setTemperature(0f)
            .setRandomSeed(handle.seed)
            .build()
        val session = LlmInferenceSession.createFromOptions(handle.llm, sessionOptions)
        try {
            session.addQueryChunk(prompt)
            val done = CompletableDeferred<String>()
            val idle = CompletableDeferred<Unit>()
            val text = StringBuilder()
            var firstToken = 0L
            var end = 0L
            var steps = 0
            val cancelled = AtomicBoolean(false)
            val start = AdapterSupport.nowMicros()
            // MediaPipe has no per-request token limit (setMaxTokens is the whole
            // context, fixed at load), so maxNewTokens is enforced here: the
            // listener fires once per decode step, and generation is cancelled
            // after the requested number of steps. Nothing that calls back into
            // the runtime may run in this listener: MediaPipe throws while a
            // generation is in flight, and inside the JNI callback that aborts.
            val generation = session.generateResponseAsync { partial, finished ->
                if (!done.isCompleted) {
                    if (firstToken == 0L && partial.isNotEmpty()) firstToken = AdapterSupport.nowMicros()
                    text.append(partial)
                    if (finished || ++steps >= input.maxNewTokens) {
                        end = AdapterSupport.nowMicros()
                        cancelled.set(!finished)
                        done.complete(text.toString())
                        if (!finished) runCatching { session.cancelGenerateResponseAsync() }
                    }
                }
                if (finished) idle.complete(Unit)
            }
            val output = done.await()
            // Token counts call into the runtime, so wait until it reports the
            // generation finished (also after a cancel); the timing is already taken.
            withTimeoutOrNull(IDLE_TIMEOUT_MS) { idle.await() }
            val failure = if (cancelled.get()) null else runCatching { generation.get(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.exceptionOrNull()
            GenerationOutcome.requireAnswer(output, handle.effectiveDelegate, cancelled.get(), failure)
            val decodeTokens = runCatching { handle.llm.sizeInTokens(output) }
            AdapterSupport.textResult(
                outputText = output,
                startMicros = start,
                firstTokenMicros = if (firstToken == 0L) end else firstToken,
                endMicros = end,
                prefillTokens = runCatching { handle.llm.sizeInTokens(prompt) }.getOrElse { AdapterSupport.approximateTokenCount(prompt) },
                decodeTokens = decodeTokens.getOrElse { AdapterSupport.approximateTokenCount(output) },
            )
        } finally {
            runCatching { session.close() }
        }
    }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.IO) {
        runCatching { (model as Handle).llm.close() }
        Unit
    }

    private companion object {
        const val RUNTIME_VERSION = "0.10.27"
        const val MAX_TOTAL_TOKENS = 1024
        const val IDLE_TIMEOUT_MS = 30_000L
    }
}
