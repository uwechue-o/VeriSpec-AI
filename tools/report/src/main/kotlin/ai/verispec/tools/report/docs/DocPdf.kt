package ai.verispec.tools.report.docs

import ai.verispec.tools.report.PdfSafeText
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * A small flowing-text PDF builder: headings, paragraphs, bullets, code blocks,
 * two-column tables, page numbers and a generated table of contents.
 *
 * It exists because the manual should be produced by the project's own tooling
 * rather than by a converter, and because the report renderer's layout is
 * tuned for one-page-per-section evidence output, not prose.
 */
class DocPdf(private val title: String) {

    private val doc = PDDocument()
    private var page = newPage()
    private var stream = PDPageContentStream(doc, page)
    private var y = TOP
    private var pageNumber = 1
    private var suppressChrome = true

    private data class TocEntry(val number: String, val title: String, val page: Int, val level: Int)

    private val toc = mutableListOf<TocEntry>()
    private var sectionCounter = 0
    private var subCounter = 0

    /* ------------------------------------------------------------------ pages */

    private fun newPage(): PDPage = PDPage(PDRectangle.A4).also { doc.addPage(it) }

    private fun breakPage() {
        finishPage()
        page = newPage()
        stream = PDPageContentStream(doc, page)
        pageNumber++
        y = TOP
    }

    private fun finishPage() {
        if (!suppressChrome) {
            // Running footer: document title on the left, page number on the right.
            drawRaw(title, MARGIN, FOOTER_Y, ITALIC, 8f, FAINT)
            val label = pageNumber.toString()
            val w = ITALIC.getStringWidth(label) / 1000f * 8f
            drawRaw(label, PDRectangle.A4.width - MARGIN - w, FOOTER_Y, ITALIC, 8f, FAINT)
        }
        stream.close()
    }

    private fun ensure(space: Float) {
        if (y - space < BOTTOM) breakPage()
    }

    /* ------------------------------------------------------------------ text */

    private fun drawRaw(text: String, x: Float, baseline: Float, font: PDType1Font, size: Float, colour: Color) {
        stream.beginText()
        stream.setFont(font, size)
        stream.setNonStrokingColor(colour)
        stream.newLineAtOffset(x, baseline)
        stream.showText(PdfSafeText.sanitize(text))
        stream.endText()
    }

    private fun widthOf(text: String, font: PDType1Font, size: Float): Float =
        font.getStringWidth(PdfSafeText.sanitize(text)) / 1000f * size

    /** Greedy word wrap against the real glyph widths of the chosen font. */
    private fun wrap(text: String, font: PDType1Font, size: Float, maxWidth: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (widthOf(candidate, font, size) <= maxWidth) {
                line = StringBuilder(candidate)
            } else {
                if (line.isNotEmpty()) lines += line.toString()
                // A single word longer than the line (a long identifier) is hard-split.
                if (widthOf(word, font, size) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (widthOf("$chunk$ch", font, size) > maxWidth) {
                            lines += chunk.toString()
                            chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    line = chunk
                } else {
                    line = StringBuilder(word)
                }
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun flow(
        text: String,
        font: PDType1Font,
        size: Float,
        colour: Color,
        indent: Float = 0f,
        leading: Float = 1.45f,
    ) {
        val maxWidth = CONTENT_WIDTH - indent
        for (line in wrap(text, font, size, maxWidth)) {
            ensure(size * leading)
            drawRaw(line, MARGIN + indent, y, font, size, colour)
            y -= size * leading
        }
    }

    /* ------------------------------------------------------------------ blocks */

    fun coverPage(subtitle: String, meta: List<String>) {
        suppressChrome = true
        y = PDRectangle.A4.height - 210f
        drawRaw(title, MARGIN, y, BOLD, 30f, INK)
        y -= 34f
        flow(subtitle, REGULAR, 13f, MUTED)
        y -= 26f
        stream.setNonStrokingColor(ACCENT)
        stream.addRect(MARGIN, y, 150f, 3f)
        stream.fill()
        y -= 34f
        meta.forEach { flow(it, REGULAR, 10f, MUTED, leading = 1.7f) }
        breakPage()
        suppressChrome = false
    }

    /** Reserves a page for the contents; filled in by [finish] once page numbers are known. */
    fun contentsPlaceholder() {
        tocPageIndex = doc.numberOfPages - 1
        h1NoToc("Contents")
        breakPage()
    }

    private var tocPageIndex = -1

    fun h1(text: String) {
        sectionCounter++
        subCounter = 0
        val number = "$sectionCounter."
        ensure(64f)
        y -= 14f
        toc += TocEntry(number, text, pageNumber, 1)
        drawRaw("$number  $text", MARGIN, y, BOLD, 17f, INK)
        y -= 8f
        stream.setNonStrokingColor(ACCENT)
        stream.addRect(MARGIN, y, CONTENT_WIDTH, 1.2f)
        stream.fill()
        y -= 18f
    }

    private fun h1NoToc(text: String) {
        ensure(50f)
        drawRaw(text, MARGIN, y, BOLD, 17f, INK)
        y -= 10f
        stream.setNonStrokingColor(ACCENT)
        stream.addRect(MARGIN, y, CONTENT_WIDTH, 1.2f)
        stream.fill()
        y -= 20f
    }

    fun h2(text: String) {
        subCounter++
        val number = "$sectionCounter.$subCounter"
        ensure(46f)
        y -= 8f
        toc += TocEntry(number, text, pageNumber, 2)
        drawRaw("$number  $text", MARGIN, y, BOLD, 12.5f, INK)
        y -= 17f
    }

    /** An unnumbered heading, for appendices and asides. */
    fun h2Plain(text: String) {
        ensure(40f)
        y -= 6f
        drawRaw(text, MARGIN, y, BOLD, 12.5f, INK)
        y -= 16f
    }

    fun appendix(letter: String, text: String) {
        ensure(64f)
        y -= 14f
        toc += TocEntry("App. $letter", text, pageNumber, 1)
        drawRaw("Appendix $letter  $text", MARGIN, y, BOLD, 17f, INK)
        y -= 8f
        stream.setNonStrokingColor(ACCENT)
        stream.addRect(MARGIN, y, CONTENT_WIDTH, 1.2f)
        stream.fill()
        y -= 18f
    }

    fun p(text: String) {
        flow(text, REGULAR, 10f, INK)
        y -= 6f
    }

    /** A short lead-in line set in italics, used under a heading. */
    fun lede(text: String) {
        flow(text, ITALIC, 10f, MUTED)
        y -= 6f
    }

    fun bullets(items: List<String>) {
        items.forEach { item ->
            ensure(15f)
            drawRaw("-", MARGIN + 4f, y, REGULAR, 10f, ACCENT)
            flow(item, REGULAR, 10f, INK, indent = 16f)
            y -= 2f
        }
        y -= 5f
    }

    fun numbered(items: List<String>) {
        items.forEachIndexed { i, item ->
            ensure(15f)
            drawRaw("${i + 1}.", MARGIN + 2f, y, BOLD, 10f, ACCENT)
            flow(item, REGULAR, 10f, INK, indent = 20f)
            y -= 2f
        }
        y -= 5f
    }

    /** Fixed-width block with a tinted background: commands, file layouts, code. */
    fun code(lines: List<String>) {
        val size = 8.5f
        val leading = size * 1.5f
        val height = lines.size * leading + 12f
        ensure(height)
        stream.setNonStrokingColor(CODE_BG)
        stream.addRect(MARGIN, y - height + leading, CONTENT_WIDTH, height)
        stream.fill()
        y -= 4f
        lines.forEach { line ->
            // Long code lines are truncated rather than wrapped: wrapping a
            // command makes it unusable when copied out of the page.
            var text = line
            while (widthOf(text, MONO, size) > CONTENT_WIDTH - 16f && text.length > 4) {
                text = text.dropLast(1)
            }
            drawRaw(text, MARGIN + 8f, y, MONO, size, CODE_INK)
            y -= leading
        }
        y -= 12f
    }

    fun code(vararg lines: String) = code(lines.toList())

    /** Two-column reference table with a header row and alternating row tint. */
    fun table(headers: Pair<String, String>, rows: List<Pair<String, String>>, leftFraction: Float = 0.34f) {
        val size = 9f
        val leftWidth = CONTENT_WIDTH * leftFraction - 10f
        val rightWidth = CONTENT_WIDTH * (1 - leftFraction) - 10f

        ensure(30f)
        stream.setNonStrokingColor(HEADER_BG)
        stream.addRect(MARGIN, y - 5f, CONTENT_WIDTH, 17f)
        stream.fill()
        drawRaw(headers.first, MARGIN + 6f, y, BOLD, size, INK)
        drawRaw(headers.second, MARGIN + CONTENT_WIDTH * leftFraction + 6f, y, BOLD, size, INK)
        y -= 20f

        var shade = false
        rows.forEach { (left, right) ->
            val leftLines = wrap(left, BOLD, size, leftWidth)
            val rightLines = wrap(right, REGULAR, size, rightWidth)
            val rowLines = maxOf(leftLines.size, rightLines.size)
            val rowHeight = rowLines * size * 1.45f + 6f
            ensure(rowHeight)

            if (shade) {
                stream.setNonStrokingColor(ROW_BG)
                stream.addRect(MARGIN, y - rowHeight + size * 1.45f, CONTENT_WIDTH, rowHeight)
                stream.fill()
            }
            shade = !shade

            var ly = y
            leftLines.forEach { drawRaw(it, MARGIN + 6f, ly, BOLD, size, INK); ly -= size * 1.45f }
            var ry = y
            rightLines.forEach { drawRaw(it, MARGIN + CONTENT_WIDTH * leftFraction + 6f, ry, REGULAR, size, INK); ry -= size * 1.45f }
            y -= rowHeight
        }
        y -= 8f
    }

    /** A boxed aside for constraints the reader must not skip. */
    fun callout(label: String, text: String, colour: Color = WARN) {
        val size = 9.5f
        val lines = wrap(text, REGULAR, size, CONTENT_WIDTH - 26f)
        val height = lines.size * size * 1.45f + 26f
        ensure(height + 8f)

        stream.setNonStrokingColor(CALLOUT_BG)
        stream.addRect(MARGIN, y - height + size, CONTENT_WIDTH, height)
        stream.fill()
        stream.setNonStrokingColor(colour)
        stream.addRect(MARGIN, y - height + size, 3.5f, height)
        stream.fill()

        drawRaw(label.uppercase(), MARGIN + 12f, y, BOLD, 8f, colour)
        y -= 14f
        lines.forEach { line ->
            drawRaw(line, MARGIN + 12f, y, REGULAR, size, INK)
            y -= size * 1.45f
        }
        y -= 18f
    }

    /**
     * A raster figure scaled to [widthFraction] of the content width and centred,
     * with an italic caption under it. Kept on one page with its caption so a chart
     * is never separated from what it shows. Tall phone screenshots use a fraction
     * so they fit on a page.
     */
    fun figure(image: BufferedImage, caption: String, widthFraction: Float = 1f) {
        val width = CONTENT_WIDTH * widthFraction
        val height = width * image.height / image.width
        val captionLines = wrap(caption, ITALIC, 9f, CONTENT_WIDTH)
        ensure(height + captionLines.size * 9f * 1.45f + 16f)
        val xObject = LosslessFactory.createFromImage(doc, image)
        stream.drawImage(xObject, MARGIN + (CONTENT_WIDTH - width) / 2f, y - height + 10f, width, height)
        y -= height + 6f
        captionLines.forEach { drawRaw(it, MARGIN, y, ITALIC, 9f, MUTED); y -= 9f * 1.45f }
        y -= 10f
    }

    fun spacer(height: Float = 8f) {
        y -= height
    }

    fun pageBreak() = breakPage()

    /* ------------------------------------------------------------------ output */

    fun finish(): ByteArray {
        finishPage()
        renderContents()
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /**
     * The contents page is written last, onto the page reserved for it, because
     * entry page numbers are only known once the body has been laid out.
     */
    private fun renderContents() {
        if (tocPageIndex < 0) return
        val tocPage = doc.getPage(tocPageIndex)
        PDPageContentStream(doc, tocPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            var ty = TOP - 46f
            toc.forEach { entry ->
                if (ty < BOTTOM) return@forEach
                val indent = if (entry.level == 1) 0f else 18f
                val font = if (entry.level == 1) BOLD else REGULAR
                val size = if (entry.level == 1) 10f else 9.5f
                if (entry.level == 1) ty -= 5f

                val label = "${entry.number}  ${entry.title}"
                cs.beginText()
                cs.setFont(font, size)
                cs.setNonStrokingColor(INK)
                cs.newLineAtOffset(MARGIN + indent, ty)
                cs.showText(PdfSafeText.sanitize(label))
                cs.endText()

                val pageLabel = entry.page.toString()
                val pw = font.getStringWidth(pageLabel) / 1000f * size
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(MARGIN + CONTENT_WIDTH - pw, ty)
                cs.showText(pageLabel)
                cs.endText()

                // Leader dots between the entry and its page number.
                val labelWidth = font.getStringWidth(PdfSafeText.sanitize(label)) / 1000f * size
                val dotStart = MARGIN + indent + labelWidth + 6f
                val dotEnd = MARGIN + CONTENT_WIDTH - pw - 6f
                if (dotEnd > dotStart) {
                    cs.setNonStrokingColor(FAINT)
                    var dx = dotStart
                    while (dx < dotEnd) {
                        cs.addRect(dx, ty + 2.5f, 1f, 1f)
                        dx += 4f
                    }
                    cs.fill()
                }
                ty -= size * 1.9f
            }
        }
    }

    private companion object {
        const val MARGIN = 56f
        val TOP = PDRectangle.A4.height - 62f
        const val BOTTOM = 68f
        const val FOOTER_Y = 44f
        val CONTENT_WIDTH = PDRectangle.A4.width - 2 * MARGIN

        val REGULAR = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val BOLD = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val ITALIC = PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE)
        val MONO = PDType1Font(Standard14Fonts.FontName.COURIER)

        val INK = Color(0.09f, 0.11f, 0.13f)
        val MUTED = Color(0.36f, 0.39f, 0.43f)
        val FAINT = Color(0.62f, 0.65f, 0.68f)
        val ACCENT = Color(0.07f, 0.20f, 0.23f)
        val WARN = Color(0.78f, 0.45f, 0.05f)
        val CODE_BG = Color(0.955f, 0.965f, 0.965f)
        val CODE_INK = Color(0.12f, 0.16f, 0.18f)
        val HEADER_BG = Color(0.90f, 0.925f, 0.925f)
        val ROW_BG = Color(0.972f, 0.978f, 0.978f)
        val CALLOUT_BG = Color(0.98f, 0.965f, 0.93f)
    }
}
