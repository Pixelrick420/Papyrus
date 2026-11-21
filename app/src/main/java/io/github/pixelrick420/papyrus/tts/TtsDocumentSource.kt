package io.github.pixelrick420.papyrus.tts

import io.github.pixelrick420.papyrus.data.DocumentFormat
import io.github.pixelrick420.papyrus.data.LocationKind
import io.github.pixelrick420.papyrus.data.ReadingLocation
import io.github.pixelrick420.papyrus.viewer.OfficeBlock

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
        DocumentFormat.CSV, DocumentFormat.PPTX, DocumentFormat.UNKNOWN -> emptyList()
    }

    fun unitsFromPdfPages(pages: List<String>): List<TtsUnit> =
        pages.flatMapIndexed { index, page ->
            paragraphs(page).map { TtsUnit(it.text, index, it.start) }
        }

    fun unitsFromSlides(slides: List<String>): List<TtsUnit> =
        slides.flatMapIndexed { index, slide ->
            paragraphs(slide).map { TtsUnit(it.text, index, it.start) }
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
            // A table is spoken one row at a time: a whole table as one utterance is long and cannot be paused per row.
            val texts = when (block) {
                is OfficeBlock.Table -> block.rows
                    .map { row -> row.joinToString(" ") { it.text }.trim() }
                    .filter { it.isNotEmpty() }
                else -> listOf(
                    when (block) {
                        is OfficeBlock.Heading -> block.text
                        is OfficeBlock.Paragraph -> block.text
                        is OfficeBlock.ListItem -> block.text
                        is OfficeBlock.Image -> block.altText.orEmpty()
                        is OfficeBlock.Note -> listOfNotNull(block.text.ifBlank { null }, block.byline).joinToString(" ")
                        OfficeBlock.Divider -> ""
                        is OfficeBlock.Table -> "" // Unreachable: rows were emitted above.
                    }.trim(),
                ).filter { it.isNotEmpty() }
            }
            for (text in texts) {
                for (range in TtsUnitSplitter.split(text)) {
                    units.add(TtsUnit(text.substring(range), index, range.first))
                }
            }
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
            if (paragraph.isNotEmpty()) {
                val ranges = TtsUnitSplitter.split(paragraph)
                if (ranges.size == 1) {
                    result.add(TtsParagraph(start, sourceEnd, paragraph))
                } else {
                    // Splitting is rare, so the source map is built only for a paragraph that needs it. Walking the
                    // source and the collapsed text together keeps a sub-unit's start exact in O(n), not per sub-unit.
                    val offsets = collapsedOffsets(text, start, paragraph)
                    for (range in ranges) {
                        result.add(TtsParagraph(offsets[range.first], sourceEnd, paragraph.substring(range)))
                    }
                }
            }
            i = lineStart
        }
        return result
    }

    private fun collapsedOffsets(source: String, from: Int, collapsed: String): IntArray {
        val offsets = IntArray(collapsed.length)
        var at = from
        for (i in collapsed.indices) {
            val c = collapsed[i]
            if (c.isWhitespace()) {
                offsets[i] = at.coerceAtMost(source.lastIndex)
                if (at < source.length) at++
            } else {
                while (at < source.length && source[at].isWhitespace()) at++
                offsets[i] = at.coerceAtMost(source.lastIndex)
                at++
            }
        }
        return offsets
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
