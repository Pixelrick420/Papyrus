package com.papyrus.app.viewer

data class PdfTextPos(val page: Int, val index: Int) : Comparable<PdfTextPos> {
    override fun compareTo(other: PdfTextPos): Int =
        if (page != other.page) page.compareTo(other.page) else index.compareTo(other.index)
}

data class PageSpan(val from: Int, val to: Int)

// Build it with [between], which orders the carets and refuses an empty one.
data class PdfSelection(val start: PdfTextPos, val end: PdfTextPos) {

    val pages: IntRange get() = start.page..end.page

    // A middle page is `to == Int.MAX_VALUE` rather than its length, so it can be reported before that page's text is read; consumers clamp.
    fun spanOn(page: Int): PageSpan? {
        if (page !in pages) return null
        return PageSpan(
            from = if (page == start.page) start.index else 0,
            to = if (page == end.page) end.index else Int.MAX_VALUE,
        )
    }

    companion object {
        fun between(a: PdfTextPos, b: PdfTextPos): PdfSelection? = when {
            a < b -> PdfSelection(a, b)
            b < a -> PdfSelection(b, a)
            else -> null
        }
    }
}

data class PagePoint(val page: Int, val x: Float, val y: Float, val aspect: Float)

class PdfPageGeometry(
    private val pageCount: Int,
    widthPx: Float,
    private val gapPx: Float,
    private val aspectOf: (Int) -> Float,
) {
    private val widthPx = widthPx.coerceAtLeast(1f)

    fun heightOf(page: Int): Float = (widthPx * aspectOf(page)).coerceAtLeast(1f)

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
