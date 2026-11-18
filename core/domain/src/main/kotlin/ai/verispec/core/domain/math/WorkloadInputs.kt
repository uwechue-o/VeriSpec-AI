package ai.verispec.core.domain.math

import ai.verispec.core.domain.model.RunManifest
import ai.verispec.core.domain.model.Workload
import ai.verispec.core.domain.model.WorkloadInput

/**
 * Derives the input for iteration N purely from the manifest. Same manifest,
 * same iteration, same bytes on every device: that is what makes cross-device
 * output digests comparable.
 */
object WorkloadInputs {

    fun forIteration(manifest: RunManifest, iterationIndex: Int): WorkloadInput =
        when (val w = manifest.workload) {
            is Workload.TextGeneration -> WorkloadInput(
                iterationIndex = iterationIndex,
                prompt = w.prompts[iterationIndex % w.prompts.size],
                tensor = null,
                tensorShape = null,
                maxNewTokens = w.maxNewTokens,
            )

            is Workload.TensorInference -> WorkloadInput(
                iterationIndex = iterationIndex,
                prompt = null,
                tensor = deterministicTensor(
                    seed = manifest.seed,
                    iterationIndex = iterationIndex,
                    size = w.elementCount,
                    min = w.inputMin,
                    max = w.inputMax,
                ),
                tensorShape = w.inputShape,
                maxNewTokens = 0,
            )
        }

    /**
     * SplitMix64 stream seeded from (seed, iteration). Chosen because it is
     * trivially portable to C/Python for independent reproduction.
     */
    fun deterministicTensor(seed: Long, iterationIndex: Int, size: Int, min: Double, max: Double): FloatArray {
        var state = seed xor (iterationIndex.toLong() * GOLDEN_GAMMA)
        val span = max - min
        return FloatArray(size) {
            state += GOLDEN_GAMMA
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            val unit = (z ushr 11).toDouble() / (1L shl 53).toDouble() // [0, 1)
            (min + unit * span).toFloat()
        }
    }

    private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
}
