package ai.verispec.data.runtime.litert

import android.os.Process
import android.util.Log

/**
 * How many of the model's operations the NNAPI delegate took, read from LiteRT's own
 * log line ("Replacing 62 out of 62 node(s) with delegate (TfLiteNnapiDelegate) node,
 * yielding 1 partitions"). LiteRT's Java API cannot report it, and without it a bundle
 * would credit NNAPI, or a named device, with a run that the device refused outright:
 * on the OnePlus 8T, qti-hta takes no float32 operation and LiteRT quietly runs all
 * 62 on XNNPACK. An app may read its own process's log, so this needs no permission.
 */
internal object DelegationLog {

    sealed interface Outcome {
        /** NNAPI took [delegated] of [total] operations, summed over subgraphs. */
        data class Taken(val delegated: Int, val total: Int, val partitions: Int) : Outcome
        /** The log was read after the marker and NNAPI took nothing. */
        data object NoneTaken : Outcome
        /** The marker never appeared, so nothing can be said either way. */
        data object NotRead : Outcome
    }

    private const val TAG = "VerispecLiteRt"
    private val NNAPI_LINE = Regex("""Replacing (\d+) out of (\d+) node\(s\) with delegate \(TfLiteNnapiDelegate\) node, yielding (\d+) partitions""")
    private val ANY_DELEGATE_LINE = Regex("""Replacing \d+ out of \d+ node\(s\) with delegate \(""")

    /** Writes a marker before the interpreter is built, so only lines after it are read. */
    fun mark(): String = "load-${System.nanoTime()}".also { Log.i(TAG, it) }

    /**
     * Reads this process's log after [marker]. Logd delivers asynchronously, so it waits
     * briefly for a delegate line (XNNPACK logs one whenever it takes over the graph).
     */
    fun read(marker: String): Outcome {
        var last: Outcome = Outcome.NotRead
        repeat(ATTEMPTS) {
            val lines = runCatching { dump() }.getOrNull() ?: return Outcome.NotRead
            last = parse(lines, marker)
            if (last is Outcome.Taken || (last == Outcome.NoneTaken && lines.afterMarker(marker).any { ANY_DELEGATE_LINE.containsMatchIn(it) })) {
                return last
            }
            Thread.sleep(WAIT_MILLIS)
        }
        return last
    }

    /** Pure, for tests: the outcome for the log lines following [marker]. */
    fun parse(lines: List<String>, marker: String): Outcome {
        if (lines.none { it.contains(marker) }) return Outcome.NotRead
        val matches = lines.afterMarker(marker).mapNotNull { NNAPI_LINE.find(it) }
        if (matches.isEmpty()) return Outcome.NoneTaken
        return Outcome.Taken(
            delegated = matches.sumOf { it.groupValues[1].toInt() },
            total = matches.sumOf { it.groupValues[2].toInt() },
            partitions = matches.sumOf { it.groupValues[3].toInt() },
        )
    }

    private fun List<String>.afterMarker(marker: String) = dropWhile { !it.contains(marker) }.drop(1)

    private fun dump(): List<String> {
        val process = ProcessBuilder("logcat", "-d", "--pid=${Process.myPid()}", "-s", "tflite:I", "$TAG:I")
            .redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().use { it.readLines() }.also { process.waitFor() }
    }

    private const val ATTEMPTS = 10
    private const val WAIT_MILLIS = 100L
}
