package ai.verispec.data.runtime.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class AdapterSupportThroughputTest : StringSpec({

    "tensor throughput is inferences per second over the whole pass" {
        val result = AdapterSupport.tensorResult(FloatArray(14_336), startMicros = 1_000, endMicros = 3_500, inputElements = 49_152)
        result.timeToFirstTokenMicros shouldBe 2_500
        result.decodeTokensPerSecond shouldBe (400.0 plusOrMinus 1e-9)
    }

    "tensor throughput falls as the pass slows down" {
        val fast = AdapterSupport.tensorResult(FloatArray(10), 0, 2_000, 10)
        val slow = AdapterSupport.tensorResult(FloatArray(10), 0, 4_000, 10)
        slow.decodeTokensPerSecond shouldBe (fast.decodeTokensPerSecond / 2 plusOrMinus 1e-9)
    }

    "text throughput still covers only the decode phase" {
        val result = AdapterSupport.textResult("out", startMicros = 0, firstTokenMicros = 200_000, endMicros = 1_200_000, prefillTokens = 8, decodeTokens = 50)
        result.decodeTokensPerSecond shouldBe (50.0 plusOrMinus 1e-9)
    }
})
