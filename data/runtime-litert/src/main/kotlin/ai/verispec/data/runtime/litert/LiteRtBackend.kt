package ai.verispec.data.runtime.litert

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
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.TensorFlowLite
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT adapter (tensor-in / tensor-out). Delegates offered: CPU (XNNPACK
 * default), NNAPI where the platform still ships it. GPU requires the
 * `litert-gpu` artifact and is reported as a gap rather than assumed.
 */
@Singleton
class LiteRtBackend @Inject constructor() : InferenceBackend {

    override val id: RuntimeId = RuntimeId.LITERT

    private class Handle(
        ref: ModelRef,
        requested: Delegate,
        effective: Delegate,
        version: String?,
        val interpreter: Interpreter,
        val nnapi: NnApiDelegate?,
    ) : SimpleLoadedModel(ref, requested, effective, version, interpreter)

    override suspend fun capabilities(): BackendCapabilities = withContext(Dispatchers.Default) {
        val version = runCatching { TensorFlowLite.runtimeVersion() }.getOrNull()
            ?: return@withContext BackendCapabilities.unavailable(id, "LiteRT native library failed to load")
        val delegates = buildSet {
            add(Delegate.CPU)
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O_MR1..NNAPI_LAST_SUPPORTED_SDK) add(Delegate.NNAPI)
        }
        BackendCapabilities(
            runtime = id,
            available = true,
            unavailableReason = null,
            runtimeVersion = version,
            supportedFormats = setOf(ModelFormat.TFLITE, ModelFormat.LITERT),
            supportedDelegates = delegates,
            maxThreads = Runtime.getRuntime().availableProcessors(),
            supportsTextGeneration = false,
            supportsTensorInference = true,
            supportsLogitProjection = true,
            notes = buildList {
                add("GPU delegate not compiled into this build (litert-gpu artifact absent)")
                if (Delegate.NNAPI !in delegates) add("NNAPI delegate not offered on API ${Build.VERSION.SDK_INT}")
            },
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int): LoadedModel = withContext(Dispatchers.IO) {
        val file = ModelFiles.resolveAndVerify(model)
        val version = runCatching { TensorFlowLite.runtimeVersion() }.getOrNull()
        val options = Interpreter.Options().setNumThreads(threadCount)
        var nnapi: NnApiDelegate? = null
        var effective = Delegate.CPU
        if (delegate == Delegate.NNAPI) {
            try {
                nnapi = NnApiDelegate()
                options.addDelegate(nnapi)
                effective = Delegate.NNAPI
            } catch (e: Throwable) {
                nnapi = null // fall back, and the fallback is recorded via effectiveDelegate
            }
        }
        val interpreter = try {
            Interpreter(file, options)
        } catch (e: Throwable) {
            nnapi?.close()
            if (effective == Delegate.NNAPI) {
                // NNAPI refused the graph: degrade to CPU and record it.
                effective = Delegate.CPU
                Interpreter(file, Interpreter.Options().setNumThreads(threadCount))
            } else {
                throw e
            }
        }
        Handle(model, delegate, effective, version, interpreter, if (effective == Delegate.NNAPI) nnapi else null)
    }

    override suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult = withContext(Dispatchers.Default) {
        val handle = model as Handle
        val tensor = input.tensor ?: throw IllegalArgumentException("LiteRT adapter needs a tensor workload")
        val interpreter = handle.interpreter
        val inputTensor = interpreter.getInputTensor(0)
        val expected = inputTensor.shape().fold(1) { a, b -> a * b }
        require(expected == tensor.size) { "Model expects $expected input elements, workload has ${tensor.size}" }

        val inBuf = ByteBuffer.allocateDirect(tensor.size * 4).order(ByteOrder.nativeOrder())
        inBuf.asFloatBuffer().put(tensor)
        val outputTensor = interpreter.getOutputTensor(0)
        val outCount = outputTensor.shape().fold(1) { a, b -> a * b }
        val outBuf = ByteBuffer.allocateDirect(outCount * 4).order(ByteOrder.nativeOrder())

        val start = AdapterSupport.nowMicros()
        interpreter.run(inBuf, outBuf)
        val end = AdapterSupport.nowMicros()

        outBuf.rewind()
        val output = FloatArray(outCount).also { outBuf.asFloatBuffer().get(it) }
        AdapterSupport.tensorResult(output, start, end, tensor.size)
    }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.IO) {
        val handle = model as Handle
        runCatching { handle.interpreter.close() }
        runCatching { handle.nnapi?.close() }
        Unit
    }

    private companion object {
        /** NNAPI is deprecated from Android 15 and its delegate is no longer recommended beyond it. */
        const val NNAPI_LAST_SUPPORTED_SDK = 34
    }
}
