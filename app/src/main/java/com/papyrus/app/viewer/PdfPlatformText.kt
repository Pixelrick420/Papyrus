package com.papyrus.app.viewer

/**
 * A rectangle in page coordinates: points from the page's top-left, y running down - the unit the
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
     * never matches across a line break - the platform reports lines, not a reflowed paragraph.
     */
    fun from(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): PdfPageText {
        if (lines.isEmpty()) return PdfPageText.fromLines("", emptyList(), fromPlatform = true)
        return PdfPageText.fromLines(
            text = lines.joinToString("\n") { it.text.trimEnd(*LINE_TERMINATORS) },
            lineBoxes = lines.map { line -> line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) } },
            fromPlatform = true,
        )
    }

    /**
     * Whether [lines] can stand in for the PDFBox text model, which is exact to the glyph.
     *
     * The platform reports "continuous streams" of text, bounded by one rectangle per visual line,
     * or by none at all. The model needs one band of boxes per line of text, so only a page that
     * already has that shape is taken. Anything else - no text, text without a box, or a stream that
     * wraps over several lines - would give a page with nothing to touch, or a selection on the
     * wrong words, and is left to PDFBox, which is what the viewer read before the platform engine.
     */
    fun isUsable(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): Boolean {
        var found = false
        for (line in lines) {
            val text = line.text.trimEnd(*LINE_TERMINATORS)
            if (text.isBlank()) continue
            // A break inside the text is split on again, and so paired with the next run's boxes.
            if (text.any { it in LINE_TERMINATORS }) return false
            val boxes = line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) }
            if (boxes.isEmpty() || !onOneLine(boxes)) return false
            found = true
        }
        return found
    }

    /** True when [boxes] share a horizontal band: one line cut in pieces, not several lines stacked. */
    private fun onOneLine(boxes: List<NormRect>): Boolean =
        boxes.minOf { it.bottom } > boxes.maxOf { it.top }

    /**
     * True when two readings of one passage hold the same characters, whatever they put between
     * lines. The engine's selection counts characters its own way, so its answer is only taken when
     * it turns out to be the passage the viewer asked about.
     */
    fun sameCharacters(first: String, second: String): Boolean =
        first.filterNot(Char::isWhitespace) == second.filterNot(Char::isWhitespace)

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

    private val LINE_TERMINATORS = charArrayOf('\r', '\n')
}
