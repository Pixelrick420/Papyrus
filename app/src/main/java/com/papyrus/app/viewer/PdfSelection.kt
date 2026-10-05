package com.papyrus.app.viewer

/** A caret in the document: [index] characters into the text of page [page], both counted from 0. */
data class PdfTextPos(val page: Int, val index: Int) : Comparable<PdfTextPos> {
    override fun compareTo(other: PdfTextPos): Int =
        if (page != other.page) page.compareTo(other.page) else index.compareTo(other.index)
}

/** The characters of one page a selection covers: [from] up to, not including, [to]. */
data class PageSpan(val from: Int, val to: Int)

/**
 * The text between two carets, which may be on different pages: [start] up to, not including,
 * [end]. Build it with [between], which orders the carets and refuses an empty one, so the two are
 * always in reading order and never equal.
 */
data class PdfSelection(val start: PdfTextPos, val end: PdfTextPos) {

    /** The pages the selection touches, first to last. */
    val pages: IntRange get() = start.page..end.page

    /**
     * What of [page] is selected, or null if none of it. A page in the middle is all selected, which
     * is `to == Int.MAX_VALUE` rather than its length, so it can be said before that page's text
     * has been read; every consumer clamps to the page.
     */
    fun spanOn(page: Int): PageSpan? {
        if (page !in pages) return null
        return PageSpan(
            from = if (page == start.page) start.index else 0,
            to = if (page == end.page) end.index else Int.MAX_VALUE,
        )
    }

    companion object {
        /** The selection between two carets given in either order; null for the same caret, which selects nothing. */
        fun between(a: PdfTextPos, b: PdfTextPos): PdfSelection? = when {
            a < b -> PdfSelection(a, b)
            b < a -> PdfSelection(b, a)
            else -> null
        }
    }
}

/**
 * A point on a page in page-relative units, 0..1 across and down from the top-left, which is what
 * the glyph boxes use. [aspect] is that page's height over its width, so a distance can be judged
 * the way the reader sees it.
 */
data class PagePoint(val page: Int, val x: Float, val y: Float, val aspect: Float)

/**
 * Where pages sit relative to one another in the list: every page [widthPx] wide, [gapPx] apart,
 * each as tall as its own [aspectOf] says. That is all it takes to say which page a finger is on
 * when a drag leaves the page it started on, so a selection can run across a page break without
 * asking the layout where anything is.
 */
class PdfPageGeometry(
    private val pageCount: Int,
    widthPx: Float,
    private val gapPx: Float,
    private val aspectOf: (Int) -> Float,
) {
    private val widthPx = widthPx.coerceAtLeast(1f)

    fun heightOf(page: Int): Float = (widthPx * aspectOf(page)).coerceAtLeast(1f)

    /**
     * The point [xPx], [yPx] pixels from the top-left of page [page], however far outside it, as a
     * point on the page it actually lies over. Above the first page or below the last, and in the
     * gap between two, it lands on the edge of the nearer page: the nearest character is the
     * natural answer to a finger that has run off the text.
     */
    fun locate(page: Int, xPx: Float, yPx: Float): PagePoint {
        val last = (pageCount - 1).coerceAtLeast(0)
        var at = page.coerceIn(0, last)
        var y = yPx
        while (y < 0f && at > 0) {
            at--
            y += heightOf(at) + gapPx
        }
        while (at < last && y > heightOf(at) + gapPx) {
            y -= heightOf(at) + gapPx
            at++
        }
        return PagePoint(
            page = at,
            x = (xPx / widthPx).coerceIn(0f, 1f),
            y = (y / heightOf(at)).coerceIn(0f, 1f),
            aspect = aspectOf(at),
        )
    }
}
