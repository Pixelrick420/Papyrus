package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.viewer.OfficeBlock

// The anchor's meaning is the document's own [LocationKind]: page for a PDF, chunk for text or code, block for Office, character offset for Markdown.
data class TtsUnit(
    val text: String,
    val anchor: Int,
    // [text] has its whitespace collapsed, which is why this is a start and not a copy.
    val start: Int = 0,
)

data class TtsParagraph(val start: Int, val end: Int, val text: String)

// Builders are pure; a PDF's page text is read by the caller one page at a time, so a 500 MB file is never parsed whole.
object TtsDocumentSource {

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
        // A table has no reading order worth speaking, and it would be one unit of every cell.
        DocumentFormat.CSV, DocumentFormat.UNKNOWN -> emptyList()
    }

    fun unitsFromPdfPages(pages: List<String>): List<TtsUnit> =
        pages.flatMapIndexed { index, page ->
            paragraphs(page).map { TtsUnit(it.text, index, it.start) }
        }

    fun unitsFromPlainText(chunks: List<String>): List<TtsUnit> =
        chunks.flatMapIndexed { index, chunk ->
            paragraphs(chunk).map { TtsUnit(it.text, index, it.start) }
        }

    /** The same units as [unitsFromPlainText], made as they are read, so a large file is not held a second time. */
    fun unitsFromChunks(chunks: List<String>, ensureActive: () -> Unit = {}): List<TtsUnit> =
        ChunkTtsUnits.of(chunks, ensureActive)

    fun unitsFromMarkdown(text: String): List<TtsUnit> =
        paragraphs(text).map { TtsUnit(it.text, it.start, it.start) }

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

    // A page/chunk/block start lands on the first unit at or after it; a Markdown offset lands on the paragraph containing it.
    fun startIndexFor(units: List<TtsUnit>, location: ReadingLocation): Int {
        if (units.isEmpty()) return 0
        // Units made from chunks know where each chunk's own begin, so the chunks before the start are not read.
        if (units is ChunkTtsUnits && location.kind != LocationKind.CHAR) return units.firstUnitFrom(location.unit).coerceAtMost(units.lastIndex)
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

    // CRLF and a lone CR each end a line; a paragraph's own line breaks collapse to a space so the engine does not pause mid-sentence.
    fun paragraphs(text: String): List<TtsParagraph> {
        val result = ArrayList<TtsParagraph>()
        val n = text.length
        var i = 0
        while (i < n) {
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

    private fun lineEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    private fun afterNewline(text: String, end: Int): Int {
        if (end >= text.length) return end
        var i = end
        if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        return i + 1
    }

    private fun lineIsBlank(text: String, start: Int, end: Int): Boolean =
        text.substring(start, end).isBlank()
}
