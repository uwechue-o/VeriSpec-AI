package ai.verispec.core.evidence.canonical

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON: two runs producing identical observations must produce
 * byte-identical JSON before signing.
 *
 * Rules (documented so an independent implementation can be written):
 *  1. Object keys sorted by Unicode code point, ascending.
 *  2. No insignificant whitespace.
 *  3. Strings escaped minimally per RFC 8259: `"` `\` and U+0000–U+001F only
 *     (`\b \f \n \r \t` short forms, `\u00XX` otherwise). Everything else raw UTF-8.
 *  4. Numbers emitted exactly as their Kotlin/JVM `toString()` (shortest
 *     round-trip repr), with `-0.0` normalised to `0.0`. NaN/Infinity are rejected.
 *  5. Nulls are emitted explicitly. Defaults are never omitted.
 *  6. UTF-8, no BOM, no trailing newline.
 */
object CanonicalJson {

    fun encode(element: JsonElement): ByteArray = encodeToString(element).toByteArray(Charsets.UTF_8)

    fun encodeToString(element: JsonElement): String = buildString { write(element, this) }

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                var first = true
                for (key in element.keys.sortedWith(CODE_POINT_ORDER)) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, child ->
                    if (i > 0) out.append(',')
                    write(child, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    writeString(element.content, out)
                } else {
                    writeLiteral(element.content, out)
                }
            }
        }
    }

    private fun writeLiteral(content: String, out: StringBuilder) {
        when (content) {
            "true", "false", "null" -> out.append(content)
            "NaN", "Infinity", "-Infinity" ->
                throw IllegalArgumentException("Non-finite number cannot be canonicalised: $content")
            "-0.0" -> out.append("0.0")
            "-0" -> out.append("0")
            else -> {
                require(NUMBER.matches(content)) { "Not a JSON number literal: '$content'" }
                out.append(content)
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                FORM_FEED -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    /** Sort by code point, not by UTF-16 unit, so surrogate pairs order correctly. */
    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        val ai = a.codePoints().iterator()
        val bi = b.codePoints().iterator()
        while (ai.hasNext() && bi.hasNext()) {
            val c = ai.nextInt().compareTo(bi.nextInt())
            if (c != 0) return@Comparator c
        }
        when {
            ai.hasNext() -> 1
            bi.hasNext() -> -1
            else -> 0
        }
    }

    private val NUMBER = Regex("""^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$""")

    private val FORM_FEED: Char = 12.toChar()
}
