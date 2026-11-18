package ai.verispec.data.runtime.mediapipe

import ai.verispec.core.domain.model.Delegate
import java.util.concurrent.ExecutionException

/**
 * Decides whether a finished MediaPipe generation is an answer at all.
 *
 * A failed generation still ends with finished = true. MediaPipe 0.10.27 ships an
 * ErrorListener but never calls it, and the returned future can succeed with "":
 * on the Pixel 6 GPU every generation of the Qwen starter fails in the native
 * detokenizer (logged, not thrown) and yields empty text. Signing that as an empty
 * answer would be false evidence, so a failed future and an empty response both
 * fail the iteration. Our own cancel at maxNewTokens also fails the future, so a
 * cancelled generation is judged by its text alone.
 *
 * Kept apart from [MediaPipeBackend] so the rule is testable without the native runtime.
 */
internal object GenerationOutcome {

    /** Throws [IllegalStateException] unless [output] is a real answer; returns it otherwise. */
    fun requireAnswer(output: String, delegate: Delegate, cancelled: Boolean, failure: Throwable?): String {
        if (!cancelled && failure != null) {
            val cause = (failure as? ExecutionException)?.cause ?: failure
            throw IllegalStateException("MediaPipe generation failed: ${cause.message ?: cause::class.simpleName}", cause)
        }
        check(output.isNotEmpty()) {
            "MediaPipe returned an empty response on $delegate; the runtime most likely failed " +
                "internally (see logcat), so this is not recorded as an answer"
        }
        return output
    }
}
