package ai.verispec.app.model

import ai.verispec.core.domain.model.ModelFormat
import ai.verispec.core.domain.model.RuntimeId

/**
 * Small public models an operator can run without bringing their own file.
 * Each entry pins the exact bytes by SHA-256: a download that does not match is
 * rejected, so a starter run names the same model on every device.
 *
 * Tensor models: only float32-in / float32-out graphs are listed, because the
 * LiteRT adapter feeds and reads float buffers. Fully quantized (uint8 I/O)
 * models would fail at inference, so they are left out until the adapter
 * handles those types.
 *
 * Every entry names the backend it runs on: LiteRT for .tflite, ExecuTorch for
 * .pte (the delegate is fixed inside the file at export), MediaPipe LLM
 * Inference for .task bundles and llama.cpp for GGUF. Text models (a null
 * [tensorShape]) run a text-generation workload. Hugging Face downloads are
 * pinned to a repository commit as well as a hash, and only ungated, openly
 * licensed repositories are listed, so the download needs no account.
 */
data class StarterModel(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    val quantScheme: String,
    /** Input shape for a tensor workload; null for a text-generation model. */
    val tensorShape: String?,
    val runtime: RuntimeId = RuntimeId.LITERT,
    val parameterCount: String = "",
) {
    /** Float weights can serve as a reference; quantized ones cannot. */
    val isFloatingPoint: Boolean get() = quantScheme.startsWith("FP")
    val isTextGeneration: Boolean get() = tensorShape == null
}

object StarterModels {

    private const val MEDIAPIPE = "https://storage.googleapis.com/mediapipe-models"
    private const val QWEN_0_5B = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/49bfd323c25c9dae2118906e1f3ffd16e0729b13"
    private const val QWEN_0_5B_GGUF = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67"

    /**
     * Tag v0.11.0 of this repository, exported for ExecuTorch 1.4, the app's runtime.
     * ExecuTorch gives no forward compatibility, so a newer export may not load
     * until the runtime is raised to match.
     */
    private const val EFFICIENTNET_V2_PTE = "https://huggingface.co/software-mansion/EfficientNetV2-S-ExecuTorch/resolve/589e2679797ba926e216747a6b31377fab7ea738"

    val all: List<StarterModel> = listOf(
        StarterModel(
            id = "blaze_face_short_range",
            title = "BlazeFace short-range",
            summary = "Face detector, 128×128 image",
            url = "$MEDIAPIPE/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite",
            fileName = "blaze_face_short_range.tflite",
            sha256 = "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f",
            sizeBytes = 229_746,
            format = ModelFormat.TFLITE,
            quantScheme = "FP16",
            tensorShape = "1,128,128,3",
        ),
        StarterModel(
            id = "mobilenet_v3_small",
            title = "MobileNet V3 small",
            summary = "Image embedder, 224×224 image",
            url = "$MEDIAPIPE/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite",
            fileName = "mobilenet_v3_small.tflite",
            sha256 = "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40",
            sizeBytes = 4_117_670,
            format = ModelFormat.TFLITE,
            quantScheme = "FP32",
            tensorShape = "1,224,224,3",
        ),
        StarterModel(
            id = "yamnet",
            title = "YAMNet",
            summary = "Audio classifier, 0.975 s of 16 kHz audio",
            url = "$MEDIAPIPE/audio_classifier/yamnet/float32/1/yamnet.tflite",
            fileName = "yamnet.tflite",
            sha256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf",
            sizeBytes = 4_126_810,
            format = ModelFormat.TFLITE,
            quantScheme = "FP32",
            tensorShape = "15600",
        ),
        StarterModel(
            id = "efficientnet_lite0",
            title = "EfficientNet-Lite0",
            summary = "Image classifier, 224×224 image",
            url = "$MEDIAPIPE/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite",
            fileName = "efficientnet_lite0.tflite",
            sha256 = "6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0",
            sizeBytes = 18_582_189,
            format = ModelFormat.TFLITE,
            quantScheme = "FP32",
            tensorShape = "1,224,224,3",
        ),
        StarterModel(
            id = "qwen2_5_0_5b_instruct_q8",
            title = "Qwen2.5-0.5B-Instruct (LLM)",
            summary = "Text generation, MediaPipe LLM Inference, int8, Apache-2.0",
            url = "$QWEN_0_5B/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            sizeBytes = 546_660_344,
            format = ModelFormat.LITERT,
            quantScheme = "INT8",
            tensorShape = null,
            runtime = RuntimeId.MEDIAPIPE,
            parameterCount = "494000000",
        ),
        StarterModel(
            id = "efficientnet_v2_s_pte",
            title = "EfficientNetV2-S (ExecuTorch)",
            summary = "Image classifier, 384×384 image, XNNPACK, float32",
            url = "$EFFICIENTNET_V2_PTE/xnnpack/efficientnet_v2_s_xnnpack_fp32.pte",
            fileName = "efficientnet_v2_s_xnnpack_fp32.pte",
            sha256 = "b8ad24dd8dee3ff525dca2df7d1435c94e9cc1bc124a0880abcbf6bc85f1ae65",
            sizeBytes = 85_714_464,
            format = ModelFormat.EXECUTORCH,
            quantScheme = "FP32",
            tensorShape = "1,3,384,384",
            runtime = RuntimeId.EXECUTORCH,
        ),
        StarterModel(
            id = "qwen2_5_0_5b_instruct_gguf_q8",
            title = "Qwen2.5-0.5B-Instruct (LLM, llama.cpp)",
            summary = "Text generation, llama.cpp GGUF, Q8_0, Apache-2.0; same model as the MediaPipe entry",
            url = "$QWEN_0_5B_GGUF/qwen2.5-0.5b-instruct-q8_0.gguf",
            fileName = "qwen2.5-0.5b-instruct-q8_0.gguf",
            sha256 = "ca59ca7f13d0e15a8cfa77bd17e65d24f6844b554a7b6c12e07a5f89ff76844e",
            sizeBytes = 675_710_816,
            format = ModelFormat.GGUF,
            quantScheme = "Q8_0",
            tensorShape = null,
            runtime = RuntimeId.LLAMA_CPP,
            parameterCount = "494000000",
        ),
    )

    fun byId(id: String): StarterModel? = all.firstOrNull { it.id == id }
}
