package com.papyrus.app.viewer

/**
 * Text → blocks. A cell ends with a cell mark (0x07) and the row's TTP follows the last cell's
 * mark, so `cell\x07cell\x07\x07` is one row; nested tables arrive as plain paragraph marks.
 * Each paragraph becomes a [OfficeBlock.Heading], an [OfficeBlock.ListItem] or an
 * [OfficeBlock.Paragraph] by its style and list, numbered in reading order by [lists].
 */
internal class DocBlockBuilder(private val maxBlocks: Int, lists: DocListTables = DocListTables.EMPTY) {
    private enum class State { PARA, ROW, BETWEEN }

    private val blocks = mutableListOf<OfficeBlock>()
    private val paragraph = StringBuilder()
    private val cell = StringBuilder()
    private val row = mutableListOf<String>()
    private val rows = mutableListOf<List<String>>()
    /** Per-depth flag: field instructions (between 0x13 and 0x14) are not content. */
    private val fieldDepth = ArrayDeque<Boolean>()
    private val counters = DocListCounters(lists)
    private var state = State.PARA
    private var tableUsable = true

    /** Where the paragraph being read began in [cell], so its list marker can go in front of it. */
    private var cellParagraphStart = 0

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
            if (!feedRange(text, at, end, run.para)) return
            at = end
            if (at >= text.length) return
        }
        feedRange(text, at, text.length, null)
    }

    /** False once the block budget is spent. */
    private fun feedRange(text: String, from: Int, to: Int, para: DocParaProps?): Boolean {
        for (i in from until to) {
            if (blocks.size >= maxBlocks) {
                full = true
                return false
            }
            append(text[i], para)
        }
        return true
    }

    fun finish(): List<OfficeBlock> {
        if (state == State.ROW) {
            if (cell.isNotEmpty()) row += cell.toString().trim('\n')
            commitRow()
        }
        flushTable()
        flushParagraph('\r', null)
        return blocks
    }

    private fun append(ch: Char, para: DocParaProps?) {
        when (ch) {
            FIELD_BEGIN -> {
                if (fieldDepth.size < MAX_FIELD_DEPTH) fieldDepth.addLast(false)
                return
            }
            FIELD_SEPARATOR -> {
                if (fieldDepth.isNotEmpty()) fieldDepth[fieldDepth.lastIndex] = true
                return
            }
            FIELD_END -> {
                if (fieldDepth.isNotEmpty()) fieldDepth.removeLast()
                return
            }
            // Marks are honoured even inside a field instruction: structure survives a
            // malformed field, while its text is dropped by the check below.
            '\r', LINE_BREAK, PAGE_BREAK -> {
                paragraphMark(ch, para)
                return
            }
            CELL_MARK -> {
                cellMark(para)
                return
            }
        }
        if (fieldDepth.isNotEmpty() && !fieldDepth.last()) return
        when {
            ch == '\t' -> target().append('\t')
            ch.code >= 0x20 -> target().append(ch)
        }
    }

    private fun target(): StringBuilder = if (state == State.ROW) cell else paragraph

    /** A paragraph mark, or a line or page break, which ends a fragment of a paragraph but not the paragraph. */
    private fun paragraphMark(mark: Char, para: DocParaProps?) {
        when (state) {
            State.ROW -> {
                val had = endParagraphIn(cell, para)
                continuation = afterMark(mark, had)
                cell.append('\n')
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
                row += paragraph.toString()
                paragraph.setLength(0)
                cell.setLength(0)
                state = State.ROW
            }
            State.ROW -> if (cell.isNotEmpty()) {
                endParagraphIn(cell, para)
                continuation = false
                row += cell.toString().trim('\n')
                cell.setLength(0)
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
    private fun endParagraphIn(buffer: StringBuilder, para: DocParaProps?): Boolean {
        if (buffer.substring(cellParagraphStart).isBlank()) return false
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
            blocks += OfficeBlock.Table(rows.map { r -> r.mapIndexed { i, text -> OfficeCell(text, column = i) } })
        }
        rows.clear()
        tableUsable = true
    }

    private fun flushParagraph(mark: Char, para: DocParaProps?) {
        val text = paragraph.toString()
        paragraph.setLength(0)
        val emitted = text.isNotBlank() && blocks.size < maxBlocks
        if (emitted) blocks += blockFor(text, para)
        continuation = afterMark(mark, emitted)
    }

    /** Whether what follows [mark] continues the paragraph: only a line or page break leaves it open. */
    private fun afterMark(mark: Char, hadText: Boolean): Boolean = mark != '\r' && (hadText || continuation)

    private fun blockFor(text: String, para: DocParaProps?): OfficeBlock {
        if (para == null) return OfficeBlock.Paragraph(text)
        val marker = markerFor(para)
        return when {
            para.headingLevel > 0 -> {
                // A numbered heading keeps its number in the text, as it does for .docx.
                val shown = if (marker.isNullOrEmpty()) text else "$marker $text"
                OfficeBlock.Heading(shown, para.headingLevel, align = para.align)
            }
            marker != null -> {
                val level = para.ilvl.coerceIn(0, DocListTables.LEVELS - 1)
                OfficeBlock.ListItem(text, marker, level, align = para.align)
            }
            else -> OfficeBlock.Paragraph(text, align = para.align, indent = indentLevel(para.leftTwips))
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
}

private const val FIELD_BEGIN = '\u0013'
private const val FIELD_SEPARATOR = '\u0014'
private const val FIELD_END = '\u0015'
private const val CELL_MARK = '\u0007'
private const val LINE_BREAK = '\u000B'
private const val PAGE_BREAK = '\u000C'
private const val MAX_FIELD_DEPTH = 32
