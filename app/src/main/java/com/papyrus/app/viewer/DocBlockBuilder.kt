package com.papyrus.app.viewer

/**
 * Text → blocks. A cell ends with a cell mark (0x07) and the row's TTP follows the last cell's
 * mark, so `cell\x07cell\x07\x07` is one row; nested tables arrive as plain paragraph marks.
 * Each paragraph becomes a [OfficeBlock.Heading] or an [OfficeBlock.Paragraph] by its style.
 */
internal class DocBlockBuilder(private val maxBlocks: Int) {
    private enum class State { PARA, ROW, BETWEEN }

    private val blocks = mutableListOf<OfficeBlock>()
    private val paragraph = StringBuilder()
    private val cell = StringBuilder()
    private val row = mutableListOf<String>()
    private val rows = mutableListOf<List<String>>()
    /** Per-depth flag: field instructions (between 0x13 and 0x14) are not content. */
    private val fieldDepth = ArrayDeque<Boolean>()
    private var state = State.PARA
    private var tableUsable = true

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
        flushParagraph(null)
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
                paragraphMark(para)
                return
            }
            CELL_MARK -> {
                cellMark()
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

    private fun paragraphMark(para: DocParaProps?) {
        when (state) {
            State.ROW -> cell.append('\n')
            State.BETWEEN -> {
                // The paragraph after a TTP proves the table ended; a next row would have
                // arrived as a cell mark first.
                flushTable()
                flushParagraph(para)
                state = State.PARA
            }
            State.PARA -> flushParagraph(para)
        }
    }

    private fun cellMark() {
        when (state) {
            State.PARA, State.BETWEEN -> {
                row += paragraph.toString()
                paragraph.setLength(0)
                cell.setLength(0)
                state = State.ROW
            }
            State.ROW -> if (cell.isNotEmpty()) {
                row += cell.toString().trim('\n')
                cell.setLength(0)
            } else {
                commitRow()
                state = State.BETWEEN
            }
        }
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

    private fun flushParagraph(para: DocParaProps?) {
        val text = paragraph.toString()
        if (text.isNotBlank() && blocks.size < maxBlocks) blocks += blockFor(text, para)
        paragraph.setLength(0)
    }

    private fun blockFor(text: String, para: DocParaProps?): OfficeBlock = when {
        para == null -> OfficeBlock.Paragraph(text)
        para.headingLevel > 0 -> OfficeBlock.Heading(text, para.headingLevel, align = para.align)
        else -> OfficeBlock.Paragraph(text, align = para.align, indent = indentLevel(para.leftTwips))
    }
}


private const val FIELD_BEGIN = '\u0013'
private const val FIELD_SEPARATOR = '\u0014'
private const val FIELD_END = '\u0015'
private const val CELL_MARK = '\u0007'
private const val LINE_BREAK = '\u000B'
private const val PAGE_BREAK = '\u000C'
private const val MAX_FIELD_DEPTH = 32
