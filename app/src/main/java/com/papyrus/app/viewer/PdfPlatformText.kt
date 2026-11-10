package com.papyrus.app.viewer

internal data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val isEmpty: Boolean get() = right <= left || bottom <= top
}

internal data class PlatformLine(val text: String, val rects: List<PageRect>)

internal data class PlatformMatch(val start: Int, val rects: List<PageRect>)

internal data class PdfPlatformSelection(val text: String, val rects: List<NormRect>)

// Deliberately free of Android types, so the conversion is exercised on the JVM instead of only on a device that has the API.
internal object PdfPlatformText {

    // A newline separates lines, so a query never matches across a line break: the platform reports lines, not a reflowed paragraph.
    fun from(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): PdfPageText {
        if (lines.isEmpty()) return PdfPageText.fromLines("", emptyList(), fromPlatform = true)
        return PdfPageText.fromLines(
            text = lines.joinToString("\n") { it.text.trimEnd(*LINE_TERMINATORS) },
            lineBoxes = lines.map { line -> line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) } },
            fromPlatform = true,
        )
    }

    // The model needs one band of boxes per line of text, so only a page that already has that shape is taken; anything else would give a page with nothing to touch.
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

    private fun onOneLine(boxes: List<NormRect>): Boolean =
        boxes.minOf { it.bottom } > boxes.maxOf { it.top }

    // The engine's selection counts characters its own way, so its answer is only taken when it turns out to be the passage the viewer asked about.
    fun sameCharacters(first: String, second: String): Boolean =
        first.filterNot(Char::isWhitespace) == second.filterNot(Char::isWhitespace)

    // Only [limit] rectangles are returned in total; matches past the cap keep an empty group so the caller still counts them.
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

    fun selection(lines: List<PlatformLine>, pageWidth: Float, pageHeight: Float): PdfPlatformSelection =
        PdfPlatformSelection(
            text = lines.joinToString("\n") { it.text },
            rects = lines.flatMap { line -> line.rects.mapNotNull { normRect(it, pageWidth, pageHeight) } },
        )

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
