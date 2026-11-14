package io.github.pixelrick420.papyrus.data

import io.github.pixelrick420.papyrus.viewer.NormRect

// Only a [HIGHLIGHT] can be written into a PDF as an annotation; a note lives in Papyrus alone.
enum class HighlightKind(val code: Int) {
    HIGHLIGHT(1),
    NOTE(2),
    ;

    companion object {
        /** [raw] is the stored `kind` column; an unknown value (a newer build's kind) draws as a highlight. */
        fun fromCode(raw: Int): HighlightKind = entries.firstOrNull { it.code == raw } ?: HIGHLIGHT
    }
}

const val MAX_HIGHLIGHT_NOTE_CHARS = 1000

data class Highlight(
    val id: Long,
    val page: Int,
    val rects: List<NormRect>,
    val kind: HighlightKind = HighlightKind.HIGHLIGHT,
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

internal fun cleanHighlightNote(raw: String): String? =
    raw.trim().take(MAX_HIGHLIGHT_NOTE_CHARS).trim().ifEmpty { null }

fun List<Highlight>.marksByPage(): Map<Int, List<Highlight>> = groupBy { it.page }

// Only a plain highlight can be an in-file annotation; a note is left behind rather than flattened into one.
fun List<Highlight>.exportableRectsByPage(): Map<Int, List<List<NormRect>>> =
    filter { it.kind == HighlightKind.HIGHLIGHT }
        .groupBy({ it.page }, { it.rects })
        .filterValues { it.isNotEmpty() }

fun List<Highlight>.touchedBy(selected: Map<Int, List<NormRect>>): List<Highlight> =
    filter { highlight ->
        val boxes = selected[highlight.page] ?: return@filter false
        boxes.any { box -> highlight.rects.any { it.touches(box) } }
    }

// The line is judged by the selection's midline, not overlap: neighbouring lines' glyph boxes overlap a little.
private fun NormRect.touches(selected: NormRect): Boolean =
    left < selected.right && selected.left < right && (selected.top + selected.bottom) / 2f in top..bottom
