package ai.verispec.data.runtime.litert

import ai.verispec.data.runtime.litert.DelegationLog.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Lines as the OnePlus 8T logged them on 2026-11-18. */
class DelegationLogTest {

    private val marker = "load-123"
    private val markerLine = "I/VerispecLiteRt(13458): $marker"

    @Test
    fun `NNAPI taking the whole graph is read as all operations`() {
        val lines = listOf(
            markerLine,
            "I/tflite  (13458): Initialized TensorFlow Lite runtime.",
            "I/tflite  (13458): Replacing 62 out of 62 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 1 partitions for subgraph 0.",
        )
        assertThat(DelegationLog.parse(lines, marker)).isEqualTo(Outcome.Taken(62, 62, 1))
    }

    @Test
    fun `a device that refuses every operation is NoneTaken, though XNNPACK took the graph`() {
        // qti-hta with a float32 model: the delegate is created, then XNNPACK runs everything.
        val lines = listOf(
            markerLine,
            "I/tflite  (17778): Replacing 62 out of 62 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 1 partitions for subgraph 0.",
        )
        assertThat(DelegationLog.parse(lines, marker)).isEqualTo(Outcome.NoneTaken)
    }

    @Test
    fun `a partial delegation sums the subgraphs`() {
        val lines = listOf(
            markerLine,
            "I/tflite  (1): Replacing 40 out of 62 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 3 partitions for subgraph 0.",
            "I/tflite  (1): Replacing 5 out of 8 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 1 partitions for subgraph 1.",
        )
        assertThat(DelegationLog.parse(lines, marker)).isEqualTo(Outcome.Taken(45, 70, 4))
    }

    @Test
    fun `only lines after this load's marker count`() {
        val lines = listOf(
            "I/tflite  (1): Replacing 62 out of 62 node(s) with delegate (TfLiteNnapiDelegate) node, yielding 1 partitions for subgraph 0.",
            markerLine,
        )
        assertThat(DelegationLog.parse(lines, marker)).isEqualTo(Outcome.NoneTaken)
    }

    @Test
    fun `without the marker nothing is claimed`() {
        assertThat(DelegationLog.parse(listOf("I/tflite  (1): unrelated"), marker)).isEqualTo(Outcome.NotRead)
    }
}
