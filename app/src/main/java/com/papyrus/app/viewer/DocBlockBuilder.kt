package com.papyrus.app.viewer

/**
 * Text → blocks. A cell ends with a cell mark (0x07) and the row's TTP follows the last cell's
 * mark, so `cell\x07cell\x07\x07` is one row; nested tables arrive as plain paragraph marks.
 * Each paragraph becomes a [OfficeBlock.Heading], an [OfficeBlock.ListItem] or an
 * [OfficeBlock.Paragraph] by its style and list, numbered in reading order by [lists], and carries
 * the character formatting of its runs as spans.
 */
internal class DocBlockBuilder(private val maxBlocks: Int, lists: DocListTables = DocListTables.EMPTY) {
    private enum class State { PARA, ROW, BETWEEN }

    /** A field being read: its instruction until the separator, then its result, which a hyperlink makes a link. */
    private class Field {
        var inResult = false
        var link: String? = null
        var overflowed = false
        val instruction = StringBuilder()
    }

    private val blocks = mutableListOf<OfficeBlock>()
    private val paragraph = RichTextBuilder()
    private val cell = RichTextBuilder()
    private val row = mutableListOf<RichSnapshot>()
    private val rows = mutableListOf<List<RichSnapshot>>()

    /** Characters of the current run not yet handed to [paragraph] or [cell], which take a run at a time. */
    private val chunk = StringBuilder()

    /** Open fields, outermost first: an instruction (between 0x13 and 0x14) is not content. */
    private val fields = ArrayDeque<Field>()
    private val counters = DocListCounters(lists)
    private var state = State.PARA
    private var tableUsable = true

    /** Where the paragraph being read began in [cell], so its list marker can go in front of it. */
    private var cellParagraphStart = 0

    /** The paragraph being read has had a character that is not whitespace: a blank one is not a block. */
    private var hasText = false

    /** A line or page break cut the paragraph: what follows is a continuation, not a new list item. */
    private var continuation = false

    /** Set once the block budget is reached, so the piece loop stops instead of feeding a dead builder. */
    var full = false
        private set

    /** Adds [text], whose characters [runs] describe in order; text past the last run has no properties. */
    fun feed(text: String, runs: Sequence<DocRun>) {
        var at = 0
        for (run in runs) {
            val end = minOf(at + run.length, text.length)
            if (!feedRange(text, at, end, run)) return
            at = end
            if (at >= text.length) return
        }
        feedRange(text, at, text.length, DocRun(text.length - at, null))
    }

    /** False once the block budget is spent. */
    private fun feedRange(text: String, from: Int, to: Int, run: DocRun): Boolean {
        for (i in from until to) {
            if (blocks.size >= maxBlocks) {
                chunk.setLength(0)
                full = true
                return false
            }
            append(text[i], run)
        }
        flushChunk(run.format)
        return true
    }

    fun finish(): List<OfficeBlock> {
        if (state == State.ROW) {
            if (!cell.isEmpty()) row += linked(cell.snapshot(::isNewline))
            commitRow()
        }
        flushTable()
        flushParagraph('\r', null)
        return blocks
    }

    private fun append(ch: Char, run: DocRun) {
        when (ch) {
            FIELD_BEGIN -> {
                flushChunk(run.format)
                if (fields.size < MAX_FIELD_DEPTH) fields.addLast(Field())
                return
            }
            FIELD_SEPARATOR -> {
                flushChunk(run.format)
                fields.lastOrNull()?.let(::beginResult)
                return
            }
            FIELD_END -> {
                flushChunk(run.format)
                if (fields.isNotEmpty()) fields.removeLast()
                return
            }
            // Marks are honoured even inside a field instruction: structure survives a
            // malformed field, while its text is dropped by the check below.
            '\r', LINE_BREAK, PAGE_BREAK -> {
                flushChunk(run.format)
                paragraphMark(ch, run.para)
                return
            }
            CELL_MARK -> {
                flushChunk(run.format)
                cellMark(run.para)
                return
            }
        }
        if (ch != '\t' && ch.code < 0x20) return
        val open = fields.lastOrNull()
        when {
            open == null || open.inResult -> chunk.append(ch)
            open.instruction.length < DocLinks.MAX_INSTRUCTION -> open.instruction.append(ch)
            else -> open.overflowed = true
        }
    }

    /** The separator ends a field's instruction: a HYPERLINK's target now applies to the result that follows. */
    private fun beginResult(field: Field) {
        field.inResult = true
        // An instruction cut short may have lost the end of its target, so it links nowhere.
        if (!field.overflowed) field.link = DocLinks.hyperlinkTarget(field.instruction)
        field.instruction.setLength(0)
    }

    /** Hands the pending characters, all of one run, to whatever is being built. */
    private fun flushChunk(format: CharFormat) {
        if (chunk.isEmpty()) return
        val text = chunk.toString()
        chunk.setLength(0)
        if (!hasText && text.any { !it.isWhitespace() }) hasText = true
        // The nearest enclosing hyperlink, so a field nested in a link's result stays part of the link.
        val link = fields.lastOrNull { it.inResult && it.link != null }?.link
        (if (state == State.ROW) cell else paragraph).append(text, if (link == null) format else format.copy(link = link))
    }

    /** A paragraph mark, or a line or page break, which ends a fragment of a paragraph but not the paragraph. */
    private fun paragraphMark(mark: Char, para: DocParaProps?) {
        when (state) {
            State.ROW -> {
                val had = endParagraphIn(cell, para)
                continuation = afterMark(mark, had)
                cell.append("\n")
                cellParagraphStart = cell.length
            }
            State.BETWEEN -> {
                // The paragraph after a TTP proves the table ended; a next row would have
                // arrived as a cell mark first.
                flushTable()
                flushParagraph(mark, para)
                state = State.PARA
            }
            State.PARA -> flushParagraph(mark, para)
        }
    }

    private fun cellMark(para: DocParaProps?) {
        when (state) {
            State.PARA, State.BETWEEN -> {
                cellParagraphStart = 0
                endParagraphIn(paragraph, para)
                continuation = false
                row += linked(paragraph.snapshot())
                paragraph.clear()
                cell.clear()
                state = State.ROW
            }
            State.ROW -> if (!cell.isEmpty()) {
                endParagraphIn(cell, para)
                continuation = false
                row += linked(cell.snapshot(::isNewline))
                cell.clear()
                cellParagraphStart = 0
            } else {
                commitRow()
                state = State.BETWEEN
            }
        }
    }

    /**
     * Puts the marker of the paragraph that just ended in front of its text in [buffer], from
     * [cellParagraphStart]. False if it was blank, which is not numbered.
     */
    private fun endParagraphIn(buffer: RichTextBuilder, para: DocParaProps?): Boolean {
        val had = hasText
        hasText = false
        if (!had) return false
        val marker = if (para == null) null else markerFor(para)
        if (!marker.isNullOrEmpty()) buffer.insert(cellParagraphStart, "$marker ")
        return true
    }

    private fun commitRow() {
        if (row.isNotEmpty() && rows.size < DocTextExtractor.MAX_TABLE_ROWS) rows += row.toList()
        tableUsable = rows.size < DocTextExtractor.MAX_TABLE_ROWS
        row.clear()
    }

    private fun flushTable() {
        if (rows.isNotEmpty() && tableUsable && blocks.size < maxBlocks) {
            blocks += OfficeBlock.Table(
                rows.map { r -> r.mapIndexed { i, cell -> OfficeCell(cell.text, column = i, spans = cell.spans) } },
            )
        }
        rows.clear()
        tableUsable = true
    }

    private fun flushParagraph(mark: Char, para: DocParaProps?) {
        val text = linked(paragraph.snapshot())
        paragraph.clear()
        val emitted = hasText && blocks.size < maxBlocks
        hasText = false
        if (emitted) blocks += blockFor(text, para)
        continuation = afterMark(mark, emitted)
    }

    /** Whether what follows [mark] continues the paragraph: only a line or page break leaves it open. */
    private fun afterMark(mark: Char, hadText: Boolean): Boolean = mark != '\r' && (hadText || continuation)

    private fun blockFor(text: RichSnapshot, para: DocParaProps?): OfficeBlock {
        if (para == null) return OfficeBlock.Paragraph(text.text, text.spans)
        val marker = markerFor(para)
        return when {
            para.headingLevel > 0 -> {
                // A numbered heading keeps its number in the text, as it does for .docx.
                if (marker.isNullOrEmpty()) {
                    OfficeBlock.Heading(text.text, para.headingLevel, text.spans, para.align)
                } else {
                    val shifted = shiftSpans(text.spans, marker.length + 1)
                    OfficeBlock.Heading("$marker ${text.text}", para.headingLevel, shifted, para.align)
                }
            }
            marker != null -> {
                val level = para.ilvl.coerceIn(0, DocListTables.LEVELS - 1)
                OfficeBlock.ListItem(text.text, marker, level, text.spans, para.align)
            }
            else -> OfficeBlock.Paragraph(text.text, text.spans, para.align, indentLevel(para.leftTwips))
        }
    }

    /**
     * The marker of a paragraph in a list, or null if it is in none. A continuation shows none, as
     * does a level past the last (Word's "no number") and a level whose format is none; only a
     * numbered paragraph advances the counters.
     */
    private fun markerFor(para: DocParaProps): String? {
        if (para.ilfo <= 0 || !counters.defines(para.ilfo)) return null
        if (continuation || para.ilvl >= DocListTables.LEVELS) return ""
        return counters.next(para.ilfo, para.ilvl)
    }

    private fun isNewline(c: Char): Boolean = c == '\n'

    /** [text] with the addresses written out in it made links; ones a field already linked keep their target. */
    private fun linked(text: RichSnapshot): RichSnapshot {
        val found = DocLinks.find(text.text)
        return if (found.isEmpty()) text else RichSnapshot(text.text, DocLinks.overlay(text.spans, found))
    }
}

private const val FIELD_BEGIN = '\u0013'
private const val FIELD_SEPARATOR = '\u0014'
private const val FIELD_END = '\u0015'
private const val CELL_MARK = '\u0007'
private const val LINE_BREAK = '\u000B'
private const val PAGE_BREAK = '\u000C'
private const val MAX_FIELD_DEPTH = 32
