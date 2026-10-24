package com.papyrus.app.data

import com.papyrus.app.viewer.NormRect

/** A stored highlight as the viewer draws it: the line boxes of one run of text on [page]. */
data class Highlight(val id: Long, val page: Int, val rects: List<NormRect>)

/** `left,top,right,bottom` per box, boxes joined by `;`. Float's text form is locale-free and exact. */
internal fun encodeRects(rects: List<NormRect>): String =
    rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

/** The boxes [encodeRects] wrote. A malformed entry is dropped: a damaged row draws less, it never crashes. */
internal fun decodeRects(text: String): List<NormRect> =
    text.split(';').mapNotNull { part ->
        val v = part.split(',').mapNotNull { it.toFloatOrNull() }
        if (v.size == 4) NormRect(v[0], v[1], v[2], v[3]) else null
    }

/** Every highlighted box on each page, for the page to draw. */
fun List<Highlight>.rectsByPage(): Map<Int, List<NormRect>> =
    groupBy({ it.page }, { it.rects }).mapValues { (_, boxes) -> boxes.flatten() }

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
