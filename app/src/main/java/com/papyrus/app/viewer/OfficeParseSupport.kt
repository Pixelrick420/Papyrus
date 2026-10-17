package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Namespaces stay in the raw name ("a:blip"); the branch tables match on it. */
internal typealias ParserFactory = (InputStream) -> XmlPullParser

/** A single cell cannot usefully span more columns than this; a bigger value is a bad file. */
internal const val MAX_MERGE_SPAN = 64

/** Pale gold under the words a comment is about, and a stronger shade behind its numbered marker. */
internal const val COMMENT_RANGE_FILL = 0xFFF2CC
internal const val COMMENT_MARKER_FILL = 0xFFD966

/**
 * The raised "C1" left in the text where a comment is anchored; its own format, so it never joins a link.
 */
internal val COMMENT_MARKER_FORMAT = CharFormat(
    bold = true,
    script = ScriptShift.SUPER,
    background = COMMENT_MARKER_FILL,
)

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}.*""")

/**
 * "Alice · 2024-05-01 10:15" from a comment's author and its ISO-8601 stamp, or null when it names
 * neither. Minutes are enough; the stamp's zone is dropped, and a non-date stamp is ignored.
 */
internal fun commentByline(author: String?, stamp: String?): String? {
    val who = author?.trim().orEmpty()
    val whenText = stamp?.trim()?.takeIf { ISO_DATE.matches(it) }?.take(ISO_MINUTES)?.replace('T', ' ').orEmpty()
    return listOf(who, whenText).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
}

private const val ISO_MINUTES = 16

/**
 * An image the body referenced: where it sits and what the document said. [index] is the block
 * position it was seen at, before any image was inserted.
 */
internal class PendingImage(
    val zipName: String,
    val index: Int,
    val widthDp: Float?,
    var altText: String?,
)

internal class CellContent(
    val text: String,
    val spans: List<TextSpan> = emptyList(),
    val background: Int? = null,
    val align: BlockAlign = BlockAlign.START,
)

internal enum class MergeState { NONE, RESTART, CONTINUE }

internal data class Slot(
    var text: String = "",
    val column: Int = 0,
    var colspan: Int = 1,
    var rowspan: Int = 1,
    val origin: Boolean = true,
    val spans: List<TextSpan> = emptyList(),
    val background: Int? = null,
    val align: BlockAlign = BlockAlign.START,
) {
    val lastColumn: Int get() = column + colspan - 1

    fun toCell() = OfficeCell(text, column, colspan, rowspan, spans, background, align)
}

/**
 * A grid for the table being built. A `w:vMerge` continuation lives in a later row, so [Slot] is
 * mutable and reached via [openCellAt]; its placeholder is dropped by [emit], never emitted.
 */
internal class Grid {
    val rows = mutableListOf<List<Slot>>()
    var row = mutableListOf<Slot>()

    /** grid column -> the Slot that started the vertical merge, for columns merged so far. */
    private val mergeOrigins = HashMap<Int, Slot>()

    val isEmpty: Boolean get() = row.isEmpty()

    fun startRow() {
        row = mutableListOf()
    }

    /**
     * Closes the outermost table's current row; only depth-1 rows are committed, so nesting cannot
     * multiply the row count. False once the row cap is reached, so the caller can drop the table.
     */
    fun commitRow(rowLimit: Int): Boolean {
        if (row.isNotEmpty() && rows.size < rowLimit) rows += row.toList()
        // A merge that no longer reaches this row is over; without pruning, a ragged table could
        // resurrect a stale origin and hand a fresh cell an inflated rowspan.
        val placed = row.mapTo(HashSet()) { it.column }
        mergeOrigins.keys.retainAll(placed)
        row = mutableListOf()
        return rows.size < rowLimit
    }

    /**
     * Records the cell ending here; only RESTART opens a new merge origin, anything else ends the merge at this column.
     */
    fun openCellAt(column: Int, content: CellContent, colspan: Int, merge: MergeState): Slot {
        val origin = mergeOrigins[column]
        if (merge == MergeState.CONTINUE && origin != null) {
            origin.rowspan++
            val placeholder = Slot(column = column, colspan = colspan, origin = false)
            row += placeholder
            return placeholder
        }
        val slot = Slot(
            text = content.text,
            column = column,
            colspan = colspan,
            spans = content.spans,
            background = content.background,
            align = content.align,
        )
        row += slot
        if (merge == MergeState.RESTART) mergeOrigins[column] = slot else mergeOrigins.remove(column)
        return slot
    }

    /** Grows the row's last cell to absorb an ODT `table:covered-table-cell` beside it. */
    fun widenLastColumn() {
        val last = row.lastOrNull() ?: return
        if (!last.origin) return
        row[row.lastIndex] = last.copy(colspan = (last.colspan + 1).coerceAtMost(MAX_MERGE_SPAN))
    }

    fun emit(): OfficeBlock.Table =
        OfficeBlock.Table(rows.map { r -> r.filter { it.origin }.map { it.toCell() } })

    fun reset() {
        rows.clear()
        row = mutableListOf()
        mergeOrigins.clear()
    }
}
