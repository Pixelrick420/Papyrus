package io.github.pixelrick420.papyrus.viewer

// A cell ends with 0x07 and the row with one more, but those marks look like paragraph marks, so paragraph properties (`sprmPFInTable`, `sprmPFTtp`) decide;
// a document without those flags is read by the old rule that an empty cell mark ends a row.
internal class DocBlockBuilder(private val maxBlocks: Int, lists: DocListTables = DocListTables.EMPTY) {
    private enum class State { PARA, ROW, BETWEEN }

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

    private val chunk = StringBuilder()

    /** Open fields, outermost first: an instruction (between 0x13 and 0x14) is not content. */
    private val fields = ArrayDeque<Field>()
    private val counters = DocListCounters(lists)
    private var state = State.PARA
    private var tableUsable = true

    private var cellParagraphStart = 0

    private var hasText = false

    /** A line or page break cut the paragraph: what follows is a continuation, not a new list item. */
    private var continuation = false

    var full = false
        private set

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
        if (state != State.ROW && run.para?.inTable == true) openRow()
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

    private fun openRow() {
        cell.clear()
        if (!paragraph.isEmpty()) {
            val pending = paragraph.snapshot()
            cell.appendRich(pending.text, pending.spans)
            paragraph.clear()
        }
        cellParagraphStart = 0
        continuation = false
        state = State.ROW
    }

    private fun beginResult(field: Field) {
        field.inResult = true
        // An instruction cut short may have lost the end of its target, so it links nowhere.
        if (!field.overflowed) field.link = DocLinks.hyperlinkTarget(field.instruction)
        field.instruction.setLength(0)
    }

    private fun flushChunk(format: CharFormat) {
        if (chunk.isEmpty()) return
        val text = chunk.toString()
        chunk.setLength(0)
        if (!hasText && text.any { !it.isWhitespace() }) hasText = true
        // The nearest enclosing hyperlink, so a field nested in a link's result stays part of the link.
        val link = fields.lastOrNull { it.inResult && it.link != null }?.link
        (if (state == State.ROW) cell else paragraph).append(text, if (link == null) format else format.copy(link = link))
    }

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
            State.ROW -> if (endsRow(para)) {
                // Text with no cell mark of its own before the row's end mark is still a cell.
                if (!cell.isEmpty()) endCell(para)
                commitRow()
                state = State.BETWEEN
            } else {
                endCell(para)
            }
        }
    }

    private fun endsRow(para: DocParaProps?): Boolean =
        if (para != null && para.inTable) para.rowEnd else cell.isEmpty()

    private fun endCell(para: DocParaProps?) {
        endParagraphIn(cell, para)
        continuation = false
        row += linked(cell.snapshot(::isNewline))
        cell.clear()
        cellParagraphStart = 0
    }

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

    private fun markerFor(para: DocParaProps): String? {
        if (para.ilfo <= 0 || !counters.defines(para.ilfo)) return null
        if (continuation || para.ilvl >= DocListTables.LEVELS) return ""
        return counters.next(para.ilfo, para.ilvl)
    }

    private fun isNewline(c: Char): Boolean = c == '\n'

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
