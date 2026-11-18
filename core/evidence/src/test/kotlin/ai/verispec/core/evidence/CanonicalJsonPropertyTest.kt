package ai.verispec.core.evidence

import ai.verispec.core.evidence.canonical.CanonicalJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single most common place these tools break is canonical serialization,
 * so it is property-tested rather than example-tested.
 */
class CanonicalJsonPropertyTest : StringSpec({

    val arbLeaf: Arb<JsonElement> = Arb.choice(
        Arb.string(0..24).map { JsonPrimitive(it) },
        Arb.int().map { JsonPrimitive(it) },
        Arb.long().map { JsonPrimitive(it) },
        Arb.double(-1e12, 1e12).map { JsonPrimitive(it) },
        Arb.boolean().map { JsonPrimitive(it) },
        arbitrary { JsonNull },
    )

    fun arbElement(depth: Int): Arb<JsonElement> =
        if (depth == 0) {
            arbLeaf
        } else {
            Arb.choice(
                arbLeaf,
                Arb.list(arbElement(depth - 1), 0..5).map { JsonArray(it) },
                Arb.map(Arb.string(1..8), arbElement(depth - 1), 0, 6).map { JsonObject(it) },
            )
        }

    val arbDocument = arbElement(3)

    "encoding is deterministic: same element, same bytes" {
        checkAll(200, arbDocument) { element ->
            CanonicalJson.encode(element) shouldBe CanonicalJson.encode(element)
        }
    }

    "encoding is independent of object key insertion order" {
        checkAll(200, Arb.map(Arb.string(1..8), arbLeaf, 1, 8)) { entries ->
            val forward = JsonObject(LinkedHashMap(entries))
            val reversed = JsonObject(LinkedHashMap(entries.entries.reversed().associate { it.key to it.value }))
            CanonicalJson.encodeToString(forward) shouldBe CanonicalJson.encodeToString(reversed)
        }
    }

    "canonical output round-trips through a standard JSON parser to the same element" {
        checkAll(200, arbDocument) { element ->
            val text = CanonicalJson.encodeToString(element)
            val reparsed = Json.parseToJsonElement(text)
            CanonicalJson.encodeToString(reparsed) shouldBe text
        }
    }

    "canonical output contains no insignificant whitespace" {
        checkAll(100, Arb.map(Arb.string(1..8), Arb.int().map { JsonPrimitive(it) }, 1, 8)) { entries ->
            val text = CanonicalJson.encodeToString(JsonObject(entries))
            val outsideStrings = text.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
            outsideStrings.any { it.isWhitespace() } shouldBe false
        }
    }

    "keys are sorted by code point" {
        val obj = JsonObject(mapOf("b" to JsonNull, "a" to JsonNull, "B" to JsonNull, "aa" to JsonNull, "é" to JsonNull))
        CanonicalJson.encodeToString(obj) shouldBe """{"B":null,"a":null,"aa":null,"b":null,"é":null}"""
    }

    "negative zero is normalised and non-finite numbers are rejected" {
        CanonicalJson.encodeToString(JsonPrimitive(-0.0)) shouldBe "0.0"
        shouldThrow<IllegalArgumentException> { CanonicalJson.encodeToString(JsonPrimitive(Double.NaN)) }
        shouldThrow<IllegalArgumentException> { CanonicalJson.encodeToString(JsonPrimitive(Double.POSITIVE_INFINITY)) }
    }

    "control characters are escaped, everything else is raw UTF-8" {
        CanonicalJson.encodeToString(JsonPrimitive("a\nb\tcd\"e\\f€")) shouldBe
            "\"a\\nb\\tc\\u0001d\\\"e\\\\f€\""
    }
})
