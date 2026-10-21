package com.papyrus.app.viewer

/** A delimited file as rows of cell text. [truncated]: rows, columns or characters were left out. */
class DelimitedRows(val rows: List<List<String>>, val truncated: Boolean)

/**
 * CSV and TSV, read for the table viewer. Quoting follows RFC 4180 (`""` is a quote, a quoted cell may
 * hold the delimiter and line breaks) and is lenient where real files are not: a quote inside an
 * unquoted cell is text, characters after a closing quote stay in the cell, and an unterminated
 * quote runs to the end of the file instead of failing, so every file opens.
 *
 * The limits exist because the table is held whole, one object per cell, and drawn by a renderer
 * that sizes columns from every row: a file past them is cut at a row boundary and the viewer ends
 * with its incomplete-document notice.
 */
object DelimitedFile {

    /** Cells kept; the row that reaches this is the last one shown. */
    const val MAX_CELLS = 200_000

    /** Columns kept per row, so one enormous line cannot become millions of cells. */
    const val MAX_COLUMNS = 1_024

    /** Characters kept per cell. A stray quote swallows everything after it into one cell; this bounds it. */
    const val MAX_CELL_CHARS = 10_000

    /**
     * `.tsv` is tab-separated, whatever it holds. Anything else is sniffed, because a `.csv` is
     * often not comma-separated: Excel writes semicolons in locales whose decimal mark is a comma.
     */
    fun delimiterFor(displayName: String?, text: String): Char {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (extension == "tsv") TAB else sniff(text)
    }

    /**
     * Reads [text] split at [delimiter]. [cut] says the text ended where a size limit cut the file,
     * so its last row may be half a row: it is dropped (unless it is the only one) and the result
     * is [DelimitedRows.truncated].
     *
     * Never yields an empty row: a blank line is skipped, and every other record has a cell.
     */
    fun parse(text: String, delimiter: Char, maxCells: Int = MAX_CELLS, cut: Boolean = false): DelimitedRows {
        val read = RowReader(text, delimiter, maxCells, Int.MAX_VALUE).read()
        if (!cut) return read
        val rows = if (read.rows.size > 1) read.rows.subList(0, read.rows.size - 1) else read.rows
        return DelimitedRows(rows, truncated = true)
    }

    /** One table block, its first row bold as the header; no blocks for a file with no rows. */
    fun toBlocks(rows: List<List<String>>): List<OfficeBlock> {
        if (rows.isEmpty()) return emptyList()
        val cells = rows.mapIndexed { rowIndex, row ->
            row.mapIndexed { column, text ->
                // Weight only, no colour, so the header reads in both themes.
                val spans = if (rowIndex == 0 && text.isNotEmpty()) listOf(TextSpan(0, text.length, HEADER)) else emptyList()
                OfficeCell(text, column = column, spans = spans)
            }
        }
        return listOf(OfficeBlock.Table(cells))
    }

    /**
     * The candidate that splits the first records into the same number of cells, more than one,
     * most consistently. Ties go to the earlier candidate, so a file nothing explains stays a
     * comma file. A header row of words is what tells `1,5;2,5` (semicolons, decimal commas) from
     * three comma-separated cells: its comma count disagrees with the rows under it.
     */
    private fun sniff(text: String): Char {
        val sample = sampleOf(text)
        var best = COMMA
        var bestScore = 0f
        for (candidate in CANDIDATES) {
            val counts = RowReader(sample, candidate, Int.MAX_VALUE, SNIFF_ROWS).read().rows.map { it.size }
            if (counts.isEmpty()) continue
            val modal = counts.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key })) ?: continue
            if (modal.key < 2) continue
            val score = modal.value.toFloat() / counts.size
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /** The start of [text], cut at a line break so a record is not left half read. */
    private fun sampleOf(text: String): String {
        if (text.length <= SNIFF_CHARS) return text
        val lineEnd = text.lastIndexOf('\n', SNIFF_CHARS)
        return text.substring(0, if (lineEnd > 0) lineEnd else SNIFF_CHARS)
    }

    private const val COMMA = ','
    private const val TAB = '\t'
    private val CANDIDATES = charArrayOf(COMMA, ';', TAB, '|')

    /** Records read to choose a delimiter, and the characters they may span. */
    private const val SNIFF_ROWS = 50
    private const val SNIFF_CHARS = 64 * 1024

    private val HEADER = CharFormat(bold = true)
}

/** One pass over the text; every state it keeps is about the record being read. */
private class RowReader(
    private val text: String,
    private val delimiter: Char,
    private val maxCells: Int,
    private val maxRows: Int,
) {
    private val rows = ArrayList<List<String>>()
    private var row = ArrayList<String>()
    private val cell = StringBuilder()

    /** The cell being read opened with a quote, so `""` is a value and not a blank line. */
    private var quoted = false

    /** Characters of the cell being read were dropped at [DelimitedFile.MAX_CELL_CHARS]. */
    private var cellCut = false
    private var cells = 0
    private var truncated = false
    private var at = 0

    fun read(): DelimitedRows {
        // A byte-order mark the decoder left in is not part of the first cell.
        if (text.startsWith(BOM)) at = 1
        while (at < text.length) {
            val c = text[at]
            when {
                c == '"' && cell.isEmpty() && !quoted -> readQuoted()
                c == delimiter -> {
                    endCell()
                    at++
                }
                c == '\n' || c == '\r' -> {
                    at += if (c == '\r' && text.getOrNull(at + 1) == '\n') 2 else 1
                    if (endRow() && limitReached()) return DelimitedRows(rows, truncated)
                }
                else -> {
                    append(c)
                    at++
                }
            }
        }
        // The last record, when the file does not end with a line break.
        endRow()
        return DelimitedRows(rows, truncated)
    }

    /** From the opening quote to the closing one, or to the end of the text when it never comes. */
    private fun readQuoted() {
        quoted = true
        at++
        while (at < text.length) {
            val c = text[at]
            when {
                c == '"' && text.getOrNull(at + 1) == '"' -> {
                    append('"')
                    at += 2
                }
                c == '"' -> {
                    at++
                    return
                }
                // A line break inside a cell is one newline, which is what the table draws as a break.
                c == '\r' -> {
                    append('\n')
                    at += if (text.getOrNull(at + 1) == '\n') 2 else 1
                }
                else -> {
                    append(c)
                    at++
                }
            }
        }
    }

    private fun append(c: Char) {
        if (cell.length < DelimitedFile.MAX_CELL_CHARS) cell.append(c) else cellCut = true
    }

    private fun endCell() {
        val value = when {
            cell.isEmpty() -> ""
            cellCut -> cell.toString() + ELLIPSIS
            else -> cell.toString()
        }
        if (cellCut) truncated = true
        if (row.size < DelimitedFile.MAX_COLUMNS) row.add(value) else truncated = true
        cell.setLength(0)
        cellCut = false
        quoted = false
    }

    /** Closes the record; false for a blank line, which is not a row. */
    private fun endRow(): Boolean {
        if (row.isEmpty() && cell.isEmpty() && !quoted) return false
        endCell()
        rows.add(row)
        cells += row.size
        row = ArrayList()
        return true
    }

    /** Whether to stop after the row just closed; a limit with only blank text left is not a cut. */
    private fun limitReached(): Boolean {
        if (rows.size >= maxRows) return true
        if (cells < maxCells) return false
        if ((at until text.length).any { !text[it].isWhitespace() }) truncated = true
        return true
    }

    private companion object {
        const val BOM = '\uFEFF'
        const val ELLIPSIS = "…"
    }
}
