package ai.verispec.data.runtime.mediapipe

import ai.verispec.core.domain.model.Delegate
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

class GenerationOutcomeTest {

    @Test
    fun `a finished generation with text is an answer`() {
        assertThat(GenerationOutcome.requireAnswer("A short summary.", Delegate.CPU, cancelled = false, failure = null))
            .isEqualTo("A short summary.")
    }

    @Test
    fun `an empty response is a failure, not an empty answer`() {
        // What the Pixel 6 GPU produced: the native detokenizer failed, the future still returned "".
        val e = assertThrows(IllegalStateException::class.java) {
            GenerationOutcome.requireAnswer("", Delegate.GPU, cancelled = false, failure = null)
        }
        assertThat(e).hasMessageThat().startsWith("MediaPipe returned an empty response on GPU")
    }

    @Test
    fun `a failed future fails the generation and names the underlying cause`() {
        val cause = RuntimeException("DetokenizerCalculator failed")
        val e = assertThrows(IllegalStateException::class.java) {
            GenerationOutcome.requireAnswer("partial", Delegate.GPU, cancelled = false, failure = ExecutionException(cause))
        }
        assertThat(e).hasMessageThat().isEqualTo("MediaPipe generation failed: DetokenizerCalculator failed")
        assertThat(e).hasCauseThat().isSameInstanceAs(cause)
    }

    @Test
    fun `a failure without a message is still named`() {
        val e = assertThrows(IllegalStateException::class.java) {
            GenerationOutcome.requireAnswer("partial", Delegate.CPU, cancelled = false, failure = ExecutionException(IllegalArgumentException()))
        }
        assertThat(e).hasMessageThat().isEqualTo("MediaPipe generation failed: IllegalArgumentException")
    }

    @Test
    fun `our own cancel at maxNewTokens is not a failure`() {
        // Cancelling makes the future fail; the text gathered up to the cap is the answer.
        assertThat(
            GenerationOutcome.requireAnswer("Sixteen tokens of text", Delegate.CPU, cancelled = true, failure = CancellationException()),
        ).isEqualTo("Sixteen tokens of text")
    }

    @Test
    fun `a cancelled generation with no text is still not an answer`() {
        assertThrows(IllegalStateException::class.java) {
            GenerationOutcome.requireAnswer("", Delegate.CPU, cancelled = true, failure = CancellationException())
        }
    }
}
