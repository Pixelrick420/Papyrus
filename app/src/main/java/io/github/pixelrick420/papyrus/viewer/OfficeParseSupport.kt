package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.svg.SvgScene
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Namespaces stay in the raw name ("a:blip"); the branch tables match on it. */
internal typealias ParserFactory = (InputStream) -> XmlPullParser

/** A single cell cannot usefully span more columns than this; a bigger value is a bad file. */
internal const val MAX_MERGE_SPAN = 64

internal const val COMMENT_RANGE_FILL = 0xFFF2CC
internal const val COMMENT_MARKER_FILL = 0xFFD966

// The raised "C1" left in the text where a comment is anchored; its own format, so it never joins a link.
internal val COMMENT_MARKER_FORMAT = CharFormat(
    bold = true,
    script = ScriptShift.SUPER,
    background = COMMENT_MARKER_FILL,
)

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}.*""")

internal fun commentByline(author: String?, stamp: String?): String? {
    val who = author?.trim().orEmpty()
    val whenText = stamp?.trim()?.takeIf { ISO_DATE.matches(it) }?.take(ISO_MINUTES)?.replace('T', ' ').orEmpty()
    return listOf(who, whenText).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
}

private const val ISO_MINUTES = 16

internal class PendingImage(
    val zipName: String,
    val index: Int,
    val widthDp: Float?,
    var altText: String?,
    val scene: SvgScene? = null,
    var pageAspect: Float? = null,
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

// A `w:vMerge` continuation lives in a later row, so [Slot] is mutable and reached via [openCellAt]; its placeholder is dropped by [emit], never emitted.
internal class Grid {
    val rows = mutableListOf<List<Slot>>()
    var row = mutableListOf<Slot>()

    private val mergeOrigins = HashMap<Int, Slot>()

    val isEmpty: Boolean get() = row.isEmpty()

    fun startRow() {
        row = mutableListOf()
    }

    // Only depth-1 rows are committed, so nesting cannot multiply the row count.
    fun commitRow(rowLimit: Int): Boolean {
        if (row.isNotEmpty() && rows.size < rowLimit) rows += row.toList()
        // A merge that no longer reaches this row is over; without pruning, a ragged table could
        // resurrect a stale origin and hand a fresh cell an inflated rowspan.
        val placed = row.mapTo(HashSet()) { it.column }
        mergeOrigins.keys.retainAll(placed)
        row = mutableListOf()
        return rows.size < rowLimit
    }

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
