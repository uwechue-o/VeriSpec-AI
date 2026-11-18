package ai.verispec.data.runtime.executorch

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
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExecuTorch (.pte) adapter. The delegate is baked into the .pte at export time
 * (XNNPACK, QNN, ...), so we can only run "whatever the file was lowered to" and
 * record that honestly as CPU unless the manifest says otherwise.
 */
@Singleton
class ExecuTorchBackend @Inject constructor() : InferenceBackend {

    override val id: RuntimeId = RuntimeId.EXECUTORCH

    private class Handle(
        ref: ModelRef,
        requested: Delegate,
        effective: Delegate,
        version: String?,
        val module: Module,
    ) : SimpleLoadedModel(ref, requested, effective, version, module)

    override suspend fun capabilities(): BackendCapabilities = withContext(Dispatchers.Default) {
        val loadable = runCatching { Class.forName("org.pytorch.executorch.Module"); true }.getOrDefault(false)
        if (!loadable) return@withContext BackendCapabilities.unavailable(id, "ExecuTorch runtime classes missing")
        BackendCapabilities(
            runtime = id,
            available = true,
            unavailableReason = null,
            runtimeVersion = RUNTIME_VERSION,
            supportedFormats = setOf(ModelFormat.EXECUTORCH),
            supportedDelegates = setOf(Delegate.CPU),
            maxThreads = Runtime.getRuntime().availableProcessors(),
            supportsTextGeneration = false,
            supportsTensorInference = true,
            supportsLogitProjection = true,
            notes = listOf(
                "Delegate is fixed at export time inside the .pte; runtime cannot switch it",
                "Thread count is controlled by the runtime's thread pool, not per call",
            ),
        )
    }

    override suspend fun load(model: ModelRef, delegate: Delegate, threadCount: Int): LoadedModel = withContext(Dispatchers.IO) {
        val file = ModelFiles.resolveAndVerify(model)
        val module = Module.load(file.absolutePath)
        Handle(model, delegate, Delegate.CPU, RUNTIME_VERSION, module)
    }

    override suspend fun infer(model: LoadedModel, input: WorkloadInput): InferenceResult = withContext(Dispatchers.Default) {
        val handle = model as Handle
        val tensor = input.tensor ?: throw IllegalArgumentException("ExecuTorch adapter needs a tensor workload")
        val shape = (input.tensorShape ?: listOf(tensor.size)).map { it.toLong() }.toLongArray()
        val inputTensor = Tensor.fromBlob(tensor, shape)

        val start = AdapterSupport.nowMicros()
        val outputs = handle.module.forward(EValue.from(inputTensor))
        val end = AdapterSupport.nowMicros()

        val output = outputs.firstOrNull()?.toTensor()?.dataAsFloatArray ?: FloatArray(0)
        AdapterSupport.tensorResult(output, start, end, tensor.size)
    }

    override suspend fun unload(model: LoadedModel) = withContext(Dispatchers.IO) {
        runCatching { (model as Handle).module.destroy() }
        Unit
    }

    private companion object {
        /** Matches the `executorch` version in libs.versions.toml; the runtime has no version API. */
        const val RUNTIME_VERSION = "1.4.0"
    }
}
