package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.viewer.OfficeBlock

/**
 * One speakable paragraph of a document, plus the anchor the viewer follows along with. The anchor's
 * meaning is the document's own [LocationKind]: a page index for a PDF, a chunk index for plain text
 * or code, a block index for an Office document, a character offset into the rendered text for
 * Markdown.
 */
data class TtsUnit(val text: String, val anchor: Int)

/** A paragraph of a rendered/flat document: where it starts and ends, and the text to speak. */
data class TtsParagraph(val start: Int, val end: Int, val text: String)

/**
 * Turns a document into the list of paragraphs to read aloud. Every builder is pure and takes only
 * plain values (strings, chunk lists, [OfficeBlock]s), so all of this is exercised under plain JUnit.
 *
 * The one impure path — a PDF's page text — is read by the caller one page at a time through
 * `PdfPageTextSource.page(index)` and handed here as a plain list of page strings; a 500 MB file is
 * never parsed whole, and the find path's `allPages()` is never called.
 */
object TtsDocumentSource {

    /** Build the speakable units for [format] from whichever input that format uses. */
    fun units(
        format: DocumentFormat,
        markdownText: String? = null,
        plainTextChunks: List<String>? = null,
        officeBlocks: List<OfficeBlock>? = null,
        pdfPages: List<String>? = null,
    ): List<TtsUnit> = when (format) {
        DocumentFormat.PDF -> unitsFromPdfPages(pdfPages.orEmpty())
        DocumentFormat.TEXT, DocumentFormat.CODE -> unitsFromPlainText(plainTextChunks.orEmpty())
        DocumentFormat.MARKDOWN -> unitsFromMarkdown(markdownText.orEmpty())
        DocumentFormat.DOC, DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.EPUB -> unitsFromOffice(officeBlocks.orEmpty())
        DocumentFormat.UNKNOWN -> emptyList()
    }

    /** PDF: one unit per paragraph; the anchor is the page the paragraph came from. */
    fun unitsFromPdfPages(pages: List<String>): List<TtsUnit> =
        pages.flatMapIndexed { index, page ->
            paragraphs(page).map { TtsUnit(it.text, index) }
        }

    /** Plain text or code: each 40-line chunk split into its paragraphs; the anchor is the chunk. */
    fun unitsFromPlainText(chunks: List<String>): List<TtsUnit> =
        chunks.flatMapIndexed { index, chunk ->
            paragraphs(chunk).map { TtsUnit(it.text, index) }
        }

    /** Markdown: paragraph start offsets into the rendered text become the anchors. */
    fun unitsFromMarkdown(text: String): List<TtsUnit> =
        paragraphs(text).map { TtsUnit(it.text, it.start) }

    /** Office: one unit per block; tables read row-wise, notes gain their byline, images only if named. */
    fun unitsFromOffice(blocks: List<OfficeBlock>): List<TtsUnit> {
        val units = ArrayList<TtsUnit>()
        blocks.forEachIndexed { index, block ->
            val text = when (block) {
                is OfficeBlock.Heading -> block.text
                is OfficeBlock.Paragraph -> block.text
                is OfficeBlock.ListItem -> block.text
                is OfficeBlock.Table -> block.rows
                    .map { row -> row.joinToString(" ") { it.text } }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                is OfficeBlock.Image -> block.altText.orEmpty()
                is OfficeBlock.Note -> listOfNotNull(block.text.ifBlank { null }, block.byline).joinToString(" ")
                OfficeBlock.Divider -> ""
            }.trim()
            if (text.isNotEmpty()) units.add(TtsUnit(text, index))
        }
        return units
    }

    /**
     * The index of the first unit to read when playback starts at [location]. A page/chunk/block start
     * lands on the first unit at or after it; a Markdown character offset lands on the paragraph that
     * contains it. Clamped to the document, and 0 for an empty one.
     */
    fun startIndexFor(units: List<TtsUnit>, location: ReadingLocation): Int {
        if (units.isEmpty()) return 0
        return if (location.kind == LocationKind.CHAR) {
            var index = 0
            for (i in units.indices) {
                if (units[i].anchor <= location.unit) index = i
            }
            index
        } else {
            units.indexOfFirst { it.anchor >= location.unit }
                .let { if (it == -1) units.lastIndex else it }
        }
    }

    /**
     * Split [text] into paragraphs: groups of non-blank lines separated by blank lines. CRLF and a
     * lone CR each end a line, and a paragraph's own line breaks collapse to a space so the engine
     * does not pause mid-sentence. Each paragraph carries the offset it started at in [text].
     */
    fun paragraphs(text: String): List<TtsParagraph> {
        val result = ArrayList<TtsParagraph>()
        val n = text.length
        var i = 0
        while (i < n) {
            // Skip the blank lines before the next paragraph.
            while (i < n && lineIsBlank(text, i, lineEnd(text, i))) {
                i = afterNewline(text, lineEnd(text, i))
            }
            if (i >= n) break
            val start = i
            val sb = StringBuilder()
            var lineStart = i
            var sourceEnd = i
            while (lineStart < n) {
                val end = lineEnd(text, lineStart)
                val line = text.substring(lineStart, end).trim()
                if (line.isEmpty()) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(line)
                sourceEnd = end
                lineStart = afterNewline(text, end)
            }
            val paragraph = sb.toString()
            if (paragraph.isNotEmpty()) result.add(TtsParagraph(start, sourceEnd, paragraph))
            i = lineStart
        }
        return result
    }

    /** Index of the line terminator at or after [from], or the end of the string. */
    private fun lineEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    /** The start of the line after the terminator ending [end], consuming a CRLF pair as one. */
    private fun afterNewline(text: String, end: Int): Int {
        if (end >= text.length) return end
        var i = end
        if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        return i + 1
    }

    private fun lineIsBlank(text: String, start: Int, end: Int): Boolean =
        text.substring(start, end).isBlank()
}
