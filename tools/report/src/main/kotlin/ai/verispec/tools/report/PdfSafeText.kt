package ai.verispec.tools.report

/**
 * The PDF Standard-14 fonts are limited to WinAnsi, so a stray symbol such as
 * the infinity sign aborts rendering with an IllegalArgumentException mid-page.
 * Transliterate the characters this project actually emits and replace anything
 * else outside Latin-1, rather than embedding a font file for a handful of glyphs.
 */
object PdfSafeText {

    private val TRANSLITERATIONS = mapOf(
        '∞' to "inf",
        '·' to "-",
        '—' to "-",
        '–' to "-",
        '…' to "...",
        '²' to "2",
        '×' to "x",
        'µ' to "u",
        'μ' to "u",
        '→' to "->",
        '←' to "<-",
        '•' to "-",
        '≥' to ">=",
        '≤' to "<=",
        '≈' to "~",
        '§' to "S",
        ' ' to " ",
        '‘' to "'",
        '’' to "'",
        '“' to "\"",
        '”' to "\"",
        '✓' to "x",
    )

    fun sanitize(s: String): String = buildString(s.length) {
        for (ch in s) {
            when {
                TRANSLITERATIONS.containsKey(ch) -> append(TRANSLITERATIONS.getValue(ch))
                ch == '\t' -> append("    ")
                ch.isISOControl() -> append(' ')
                ch.code <= 0xFF -> append(ch)
                else -> append('?')
            }
        }
    }
}
