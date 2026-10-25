package com.papyrus.app.data

import com.papyrus.app.viewer.NormRect

/**
 * How a stored mark is drawn. A [HIGHLIGHT] is the highlighter wash; a [NOTE] is the same run with a
 * note the reader typed, drawn as an underline and a dot so the words under it stay legible. Only a
 * [HIGHLIGHT] can be written into a PDF as an annotation; a note lives in Papyrus alone.
 */
enum class HighlightKind(val code: Int) {
    HIGHLIGHT(1),
    NOTE(2),
    ;

    companion object {
        /** [raw] is the stored `kind` column; an unknown value (a newer build's kind) draws as a highlight. */
        fun fromCode(raw: Int): HighlightKind = entries.firstOrNull { it.code == raw } ?: HIGHLIGHT
    }
}

/** A note is a remark, not a document: past this it is cut rather than stored without a bound. */
const val MAX_HIGHLIGHT_NOTE_CHARS = 1000

/** A stored mark as the viewer draws it: the line boxes of one run of text on [page]. */
data class Highlight(
    val id: Long,
    val page: Int,
    val rects: List<NormRect>,
    val kind: HighlightKind = HighlightKind.HIGHLIGHT,
    /** The reader's own words for a [HighlightKind.NOTE]; null for a plain highlight. */
    val note: String? = null,
)

/** `left,top,right,bottom` per box, boxes joined by `;`. Float's text form is locale-free and exact. */
internal fun encodeRects(rects: List<NormRect>): String =
    rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

/** The boxes [encodeRects] wrote. A malformed entry is dropped: a damaged row draws less, it never crashes. */
internal fun decodeRects(text: String): List<NormRect> =
    text.split(';').mapNotNull { part ->
        val v = part.split(',').mapNotNull { it.toFloatOrNull() }
        if (v.size == 4) NormRect(v[0], v[1], v[2], v[3]) else null
    }

/** A typed note as it is stored: trimmed, capped, and blank when the reader cleared it. */
internal fun cleanHighlightNote(raw: String): String? =
    raw.trim().take(MAX_HIGHLIGHT_NOTE_CHARS).trim().ifEmpty { null }

/** Every stored mark on each page, in their stored order, for the page to draw. */
fun List<Highlight>.marksByPage(): Map<Int, List<Highlight>> = groupBy { it.page }

/**
 * The marks a PDF can carry, by page: one rectangle list per mark. Only a plain highlight is
 * representable as an in-file annotation, so a note is left behind rather than flattened into one.
 */
fun List<Highlight>.exportableRectsByPage(): Map<Int, List<List<NormRect>>> =
    filter { it.kind == HighlightKind.HIGHLIGHT }
        .groupBy({ it.page }, { it.rects })
        .filterValues { it.isNotEmpty() }

/** The highlights that any of the [selected] boxes (by page) sits on, in their stored order. */
fun List<Highlight>.touchedBy(selected: Map<Int, List<NormRect>>): List<Highlight> =
    filter { highlight ->
        val boxes = selected[highlight.page] ?: return@filter false
        boxes.any { box -> highlight.rects.any { it.touches(box) } }
    }

/**
 * Whether [selected] shares some of this box's width on the same line. The line is judged by the
 * selection's midline, not by overlap: glyph boxes of neighbouring lines overlap a little, and the
 * line under a highlighted one must not count as touching it.
 */
private fun NormRect.touches(selected: NormRect): Boolean =
    left < selected.right && selected.left < right && (selected.top + selected.bottom) / 2f in top..bottom
