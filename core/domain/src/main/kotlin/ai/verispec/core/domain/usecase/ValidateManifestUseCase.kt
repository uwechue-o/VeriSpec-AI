package ai.verispec.core.domain.usecase

import ai.verispec.core.domain.model.BackendCapabilities
import ai.verispec.core.domain.model.ManifestId
import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.Severity
import ai.verispec.core.domain.model.ValidationIssue
import ai.verispec.core.domain.model.ValidationResult
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.port.Canonicalizer
import ai.verispec.core.domain.port.Hasher

/**
 * Rejects non-reproducible manifests before wasting an hour of device time.
 * When [capabilities] is supplied the manifest is also checked against what the
 * backend actually offers on this device.
 */
class ValidateManifestUseCase(
    private val canonicalizer: Canonicalizer,
    private val hasher: Hasher,
) {
    operator fun invoke(manifest: RunManifest, capabilities: BackendCapabilities? = null): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // Identity: the id must be the hash of the content.
        val expectedId = ManifestId(hasher.sha256(canonicalizer.manifestBytes(manifest)).hex)
        if (manifest.manifestId != expectedId) {
            issues += error("manifestId", "manifestId does not match canonical content hash (expected ${expectedId.short}…)")
        }

        when (val w = manifest.workload) {
            is Workload.TextGeneration -> {
                if (w.temperature != 0.0) {
                    issues += error("workload.temperature", "Sampling temperature must be 0 (greedy) for reproducible output digests")
                }
                if (w.topK != 1) {
                    issues += error("workload.topK", "topK must be 1 (greedy) for reproducible output digests")
                }
                if (w.prompts.any { it.isBlank() }) issues += error("workload.prompts", "Prompts must not be blank")
                if (w.maxNewTokens > MAX_NEW_TOKENS_WARN) {
                    issues += warning("workload.maxNewTokens", "Very long generations make sustained runs slow; consider ≤ $MAX_NEW_TOKENS_WARN")
                }
            }
            is Workload.TensorInference -> {
                if (w.elementCount > MAX_TENSOR_ELEMENTS) {
                    issues += error("workload.inputShape", "Input tensor has ${w.elementCount} elements; maximum is $MAX_TENSOR_ELEMENTS")
                }
            }
        }

        with(manifest.schedule) {
            if (warmupIterations >= iterations) {
                issues += warning("schedule.warmupIterations", "Warmup ($warmupIterations) is not smaller than measured iterations ($iterations)")
            }
            if (iterations < MIN_ITERATIONS_FOR_ENVELOPE) {
                issues += warning("schedule.iterations", "Fewer than $MIN_ITERATIONS_FOR_ENVELOPE iterations cannot establish a performance envelope")
            }
        }

        if (manifest.backend.threadCount > MAX_THREADS) {
            issues += warning("backend.threadCount", "threadCount ${manifest.backend.threadCount} exceeds any current mobile SoC")
        }

        if (capabilities != null) {
            if (!capabilities.available) {
                issues += error("backend.runtime", "Backend ${capabilities.runtime} is not available: ${capabilities.unavailableReason ?: "unknown reason"}")
            } else {
                if (manifest.model.format !in capabilities.supportedFormats) {
                    issues += error("model.format", "${capabilities.runtime} does not load ${manifest.model.format} models")
                }
                if (manifest.backend.delegate !in capabilities.supportedDelegates) {
                    issues += error(
                        "backend.delegate",
                        "Delegate ${manifest.backend.delegate} is not offered on this device (have: ${capabilities.supportedDelegates.joinToString()})",
                    )
                }
                if (capabilities.maxThreads in 1 until manifest.backend.threadCount) {
                    issues += warning("backend.threadCount", "Backend caps threads at ${capabilities.maxThreads}")
                }
                val wantsText = manifest.workload is Workload.TextGeneration
                if (wantsText && !capabilities.supportsTextGeneration) {
                    issues += error("workload", "${capabilities.runtime} does not support text generation")
                }
                if (!wantsText && !capabilities.supportsTensorInference) {
                    issues += error("workload", "${capabilities.runtime} does not support tensor inference")
                }
            }
        }

        return ValidationResult(issues)
    }

    private fun error(field: String, message: String) = ValidationIssue(field, Severity.ERROR, message)
    private fun warning(field: String, message: String) = ValidationIssue(field, Severity.WARNING, message)

    companion object {
        const val MIN_ITERATIONS_FOR_ENVELOPE = 10
        const val MAX_NEW_TOKENS_WARN = 512
        const val MAX_TENSOR_ELEMENTS = 64 * 1024 * 1024
        const val MAX_THREADS = 32
    }
}
