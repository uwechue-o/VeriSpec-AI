package ai.verispec.core.domain.model

enum class ModelFormat { GGUF, LITERT, EXECUTORCH, TFLITE, ONNX }

enum class RuntimeId { LITERT, LITERT_LM, LLAMA_CPP, EXECUTORCH, MEDIAPIPE, ONNX_RUNTIME }

enum class Delegate { CPU, GPU, NNAPI, QNN, VENDOR_NPU }

/**
 * How the weights are represented. [scheme] is the runtime's own label
 * (Q4_K_M, INT8, FP16, FP32 ...); [isFloatingPoint] marks a reference model.
 */
data class QuantizationSpec(
    val scheme: String,
    val bitsPerWeight: Double?,
    val isFloatingPoint: Boolean,
) {
    init { require(scheme.isNotBlank()) { "Quantization scheme must not be blank" } }

    companion object {
        val FP32 = QuantizationSpec("FP32", 32.0, isFloatingPoint = true)
        val FP16 = QuantizationSpec("FP16", 16.0, isFloatingPoint = true)
        val INT8 = QuantizationSpec("INT8", 8.0, isFloatingPoint = false)
        val Q4_K_M = QuantizationSpec("Q4_K_M", 4.5, isFloatingPoint = false)
    }
}

data class ModelRef(
    val format: ModelFormat,
    val sha256: Sha256,
    val parameterCount: Long?,
    val quantization: QuantizationSpec,
    val sourceUri: String?,
)

data class BackendRef(
    val runtime: RuntimeId,
    val runtimeVersion: SemVer,
    val delegate: Delegate,
    val threadCount: Int,
    /**
     * One named device behind the delegate, e.g. "qti-hta" under NNAPI, so the run
     * goes to that device or falls back visibly instead of wherever the platform
     * chooses. Null lets the platform choose. Only NNAPI takes a name today.
     */
    val accelerator: String? = null,
) {
    init {
        require(threadCount > 0) { "threadCount must be positive" }
        require(accelerator == null || accelerator.isNotBlank()) { "accelerator must not be blank" }
        require(accelerator == null || delegate == Delegate.NNAPI) { "an accelerator can only be named for the NNAPI delegate" }
    }
}

/**
 * What the model is asked to do on every iteration. Inputs must be fully
 * determined by the workload plus the manifest seed, never by wall-clock.
 */
sealed interface Workload {
    /** LLM-style text generation. Greedy decoding is required for reproducibility. */
    data class TextGeneration(
        val prompts: List<String>,
        val maxNewTokens: Int,
        val temperature: Double = 0.0,
        val topK: Int = 1,
    ) : Workload {
        init {
            require(prompts.isNotEmpty()) { "At least one prompt is required" }
            require(maxNewTokens > 0) { "maxNewTokens must be positive" }
        }
    }

    /** Classic tensor-in/tensor-out inference. The input tensor is generated from the seed. */
    data class TensorInference(
        val inputShape: List<Int>,
        val inputMin: Double = -1.0,
        val inputMax: Double = 1.0,
    ) : Workload {
        init {
            require(inputShape.isNotEmpty() && inputShape.all { it > 0 }) { "inputShape must be non-empty and positive" }
            require(inputMin < inputMax) { "inputMin must be below inputMax" }
        }
        val elementCount: Int get() = inputShape.fold(1) { acc, dim -> acc * dim }
    }
}

data class RunSchedule(
    val iterations: Int,
    val warmupIterations: Int = 0,
    val interIterationDelayMillis: Long = 0,
    /** Optional hard stop; when reached the run completes early and records the fact. */
    val sustainedDurationMillis: Long? = null,
) {
    init {
        require(iterations > 0) { "iterations must be positive" }
        require(warmupIterations >= 0) { "warmupIterations must be non-negative" }
        require(interIterationDelayMillis >= 0) { "interIterationDelayMillis must be non-negative" }
        require(sustainedDurationMillis == null || sustainedDurationMillis > 0) { "sustainedDurationMillis must be positive" }
    }
}

/** Immutable, hashable specification of a run. The hash IS the run identity. */
data class RunManifest(
    val manifestId: ManifestId,
    val model: ModelRef,
    val backend: BackendRef,
    val workload: Workload,
    val schedule: RunSchedule,
    val seed: Long,
    val toolVersion: SemVer,
    /** Operator-supplied label. It is part of the hashed content like every other field. */
    val label: String = "",
    /**
     * The device state the run must start from. Null means no gate: the start
     * state is recorded but not controlled. Absent from the wire when null, so
     * manifests written before the gate existed keep their ids.
     */
    val preconditions: PreconditionPolicy? = null,
)

/** The concrete input for one iteration, derived deterministically from the manifest. */
class WorkloadInput(
    val iterationIndex: Int,
    val prompt: String?,
    val tensor: FloatArray?,
    val tensorShape: List<Int>?,
    val maxNewTokens: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WorkloadInput &&
            iterationIndex == other.iterationIndex &&
            prompt == other.prompt &&
            tensorShape == other.tensorShape &&
            maxNewTokens == other.maxNewTokens &&
            (if (tensor == null) other.tensor == null else other.tensor != null && tensor.contentEquals(other.tensor))

    override fun hashCode(): Int {
        var h = iterationIndex
        h = 31 * h + (prompt?.hashCode() ?: 0)
        h = 31 * h + (tensor?.contentHashCode() ?: 0)
        h = 31 * h + (tensorShape?.hashCode() ?: 0)
        h = 31 * h + maxNewTokens
        return h
    }

    override fun toString(): String =
        "WorkloadInput(iteration=$iterationIndex, prompt=${prompt?.take(32)}, tensor=${tensor?.size}, shape=$tensorShape)"
}
