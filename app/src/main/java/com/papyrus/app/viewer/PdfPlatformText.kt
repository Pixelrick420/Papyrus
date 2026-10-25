package com.papyrus.app.viewer

/**
 * A rectangle in page coordinates: points from the page's top-left, y running down — the unit the
 * platform PDF APIs report in. [PdfPlatformText] turns these into the viewer's page-relative model.
 */
internal data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** True when the rectangle encloses no area, so it can never take a highlight or a caret. */
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

/** One line of platform text: the run itself and the boxes its glyphs occupy. */
internal data class PlatformLine(val text: String, val rects: List<PageRect>)

/**
 * One platform search hit. [start] is the hit's offset in the page's platform text, which the
 * selection path uses to go back from a hit to characters; [rects] is where it is painted.
 */
internal data class PlatformMatch(val start: Int, val rects: List<PageRect>)

/** A platform selection, reduced to the text it copies and the boxes the viewer draws. */
internal data class PdfPlatformSelection(val text: String, val rects: List<NormRect>)

/**
 * Adapts the platform PDF APIs' page geometry to the viewer's model. Deliberately free of Android
 * types, so the conversion is exercised on the JVM instead of only on a device that has the API.
 */
internal object PdfPlatformText {

    /**
     * The lines joined into the per-character text model, one entry per line of [lineBoxes] and an
     * empty geometry when the platform reported no lines. A newline separates lines, so a query
     * never matches across a line break — the platform reports lines, not a reflowed paragraph.
     */
    fun from(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): PdfPageText {
        if (lines.isEmpty()) return PdfPageText.fromLines("", emptyList(), fromPlatform = true)
        return PdfPageText.fromLines(
            text = lines.joinToString("\n") { it.text },
            lineBoxes = lines.map { line -> line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) } },
            fromPlatform = true,
        )
    }

    /**
     * One group of rectangles per match, in reading order. Like the text model's own scan, only
     * [limit] rectangles are returned in total; matches past the cap keep an empty group so the
     * caller still counts them.
     */
    fun groups(
        matches: List<PlatformMatch>,
        pageWidth: Float,
        pageHeight: Float,
        limit: Int = PdfPageText.MAX_RECTS_PER_PAGE,
    ): List<List<NormRect>> {
        var remaining = limit
        return matches.map { match ->
            if (remaining <= 0) return@map emptyList()
            val rects = match.rects
                .mapNotNull { normRect(it, pageWidth, pageHeight) }
                .take(remaining)
            remaining -= rects.size
            rects
        }
    }

    /** The selection's text, and its boxes kept per line so a selection that wraps draws as several. */
    fun selection(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): PdfPlatformSelection =
        PdfPlatformSelection(
            text = lines.joinToString("\n") { it.text },
            rects = lines.flatMap { line -> line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) } },
        )

    /** [rect] as page-relative fractions, clamped to the page; null when empty, inverted or not finite. */
    fun normRect(rect: PageRect, pageWidth: Float, pageHeight: Float): NormRect? {
        if (pageWidth <= 0f || pageHeight <= 0f) return null
        if (!rect.left.isFinite() || !rect.top.isFinite()) return null
        if (!rect.right.isFinite() || !rect.bottom.isFinite()) return null
        val left = (rect.left / pageWidth).coerceIn(0f, 1f)
        val right = (rect.right / pageWidth).coerceIn(0f, 1f)
        val top = (rect.top / pageHeight).coerceIn(0f, 1f)
        val bottom = (rect.bottom / pageHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return NormRect(left, top, right, bottom)
    }
}
