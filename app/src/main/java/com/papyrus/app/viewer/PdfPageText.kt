package com.papyrus.app.viewer

import java.text.BreakIterator
import java.text.Normalizer
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A rectangle in page-relative units: 0..1 across and down, origin at the page's top-left. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The character nearest a point, and how far away it is, in page heights. */
data class CharHit(val index: Int, val distance: Float)

/** Page text plus 4 page-relative floats per character, NaN where a character has no glyph. */
class PdfPageText(
    val text: String,
    private val boxes: FloatArray,
    private val lineBreaks: BitSet = BitSet(),
) {

    init {
        require(boxes.isEmpty() || boxes.size == text.length * 4) {
            "expected 4 floats per character: ${text.length} chars, ${boxes.size} floats"
        }
    }

    fun contains(query: String): Boolean {
        val needle = normalizeQuery(query)
        return needle.isNotEmpty() && text.contains(needle, ignoreCase = true)
    }

    /** A page's answer to one query: every match counted, and the leading ones drawable. */
    data class PageMatches(val count: Int, val groups: List<List<NormRect>>)

    /**
     * One scan for both the count and the rectangles: [count] includes matches past [limit] and
     * ones whose boxes were dropped; [groups] holds one entry per match in order (empty when a
     * match has no drawable glyph) until [limit] rectangles exist.
     */
    fun matches(query: String, limit: Int = MAX_RECTS_PER_PAGE): PageMatches {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return PageMatches(0, emptyList())
        val groups = if (boxes.isEmpty() || limit <= 0) null else ArrayList<List<NormRect>>()
        var collected = 0
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            count++
            if (groups != null && collected < limit) {
                val rects = ArrayList<NormRect>()
                appendLineRects(at, at + needle.length, rects, limit - collected)
                groups += rects
                collected += rects.size
            }
            from = at + needle.length
        }
        return PageMatches(count, groups ?: emptyList())
    }

    /** Match count, including ones whose boxes were dropped; non-overlapping like [matches]. */
    fun countMatches(query: String): Int = matches(query, limit = 0).count

    /**
     * One entry per match in order, each holding that match's rectangles (one per line it covers);
     * a match with no drawable glyph keeps an empty entry. Stops once [limit] rectangles exist.
     */
    fun matchGroups(query: String, limit: Int = MAX_RECTS_PER_PAGE): List<List<NormRect>> =
        matches(query, limit).groups

    /** True when glyph boxes were kept. A page past the extractor's budget holds text without them. */
    val hasBoxes: Boolean get() = boxes.isNotEmpty()

    /** A scan has no text, and a page without boxes has nowhere for a finger to land. */
    val isSelectable: Boolean get() = boxes.isNotEmpty() && text.any { !it.isWhitespace() }

    /**
     * The box nearest ([x], [y]), or null when none exists; 0 inside a box, first overlap wins.
     * Distance is in page heights, with [aspect] (height/width) shrinking the sideways axis.
     */
    fun nearestChar(x: Float, y: Float, aspect: Float = 1f): CharHit? {
        if (boxes.isEmpty()) return null
        val sideways = if (aspect > 0f) 1f / aspect else 1f
        var best = -1
        var bestSquared = Float.MAX_VALUE
        for (line in lines) {
            if (best >= 0 && line.skippable) {
                // A char in the line sits at least as far in y as the line's band does, so once
                // the band is no closer than the best hit so far, no char in the line can beat it —
                // or tie it: lines are scanned in order and the tie always belongs to the earlier index.
                val dy = distanceToSpan(y, line.top, line.bottom)
                if (dy * dy >= bestSquared) continue
            }
            for (i in line.start until line.end) {
                val o = i * 4
                val left = boxes[o]
                if (left.isNaN()) continue
                val dx = distanceToSpan(x, left, boxes[o + 2]) * sideways
                val dy = distanceToSpan(y, boxes[o + 1], boxes[o + 3])
                val squared = dx * dx + dy * dy
                if (squared < bestSquared) {
                    best = i
                    bestSquared = squared
                }
            }
        }
        return if (best < 0) null else CharHit(best, sqrt(bestSquared))
    }

    /** Caret nearest ([x], [y]) — 0..char count, by which half of the closest box; null if none. */
    fun caretAt(x: Float, y: Float, aspect: Float = 1f): Int? {
        val hit = nearestChar(x, y, aspect) ?: return null
        val o = hit.index * 4
        return if (x < (boxes[o] + boxes[o + 2]) / 2f) hit.index else hit.index + 1
    }

    /**
     * The word holding [index] as a range, or null if not a word (whitespace or off the page).
     * The platform's own rules break it, so a Chinese run splits where a reader would.
     */
    fun wordAround(index: Int): IntRange? {
        if (index !in text.indices) return null
        val words = BreakIterator.getWordInstance()
        words.setText(text)
        val end = words.following(index)
        if (end == BreakIterator.DONE) return null
        val start = words.preceding(end)
        if (start == BreakIterator.DONE || text.substring(start, end).isBlank()) return null
        return start until end
    }

    /** One rectangle per line for [start, end); a wrapping selection is several. Both ends clamp. */
    fun selectionRects(start: Int, end: Int, limit: Int = MAX_RECTS_PER_PAGE): List<NormRect> {
        if (boxes.isEmpty()) return emptyList()
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) return emptyList()
        return ArrayList<NormRect>().also { appendLineRects(from, to, it, limit) }
    }

    /** Text of [start, end), clamped to the page; a wrap becomes a newline. */
    fun textBetween(start: Int, end: Int): String {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        val out = StringBuilder(to - from)
        for (i in from until to) out.append(if (lineBreaks[i]) '\n' else text[i])
        return out.toString()
    }

    private fun appendLineRects(start: Int, end: Int, out: MutableList<NormRect>, limit: Int) {
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        var open = false
        for (i in start until end) {
            if (lineBreaks[i]) {
                // A wrap ends the rectangle for the line just finished.
                if (open) {
                    out += NormRect(left, top, right, bottom)
                    open = false
                    if (out.size >= limit) return
                }
                continue
            }
            val o = i * 4
            val cl = boxes[o]
            if (cl.isNaN()) continue
            val ct = boxes[o + 1]
            val cr = boxes[o + 2]
            val cb = boxes[o + 3]
            if (open) {
                left = min(left, cl)
                top = min(top, ct)
                right = max(right, cr)
                bottom = max(bottom, cb)
            } else {
                left = cl
                top = ct
                right = cr
                bottom = cb
                open = true
            }
        }
        if (open && out.size < limit) out += NormRect(left, top, right, bottom)
    }

    private fun distanceToSpan(v: Float, low: Float, high: Float): Float =
        if (v < low) low - v else if (v > high) v - high else 0f

    /** One line of the page: a character range plus the vertical band its boxes span. */
    private class LineBand(val start: Int, val end: Int, val top: Float, val bottom: Float, val skippable: Boolean)

    /**
     * Lines split at the recorded breaks, built once — the class is immutable, so the index is
     * safe to hold. A line's band only vouches for chars whose box has a usable y span, so a line
     * holding a box without one is marked unskippable and always scanned in full.
     */
    private val lines: List<LineBand> by lazy { buildLineBands() }

    private fun buildLineBands(): List<LineBand> {
        val bands = ArrayList<LineBand>()
        var start = 0
        var top = 0f
        var bottom = 0f
        var open = false
        var skippable = true
        for (i in text.indices) {
            val o = i * 4
            val boxed = !boxes[o].isNaN()
            val measurable = boxed && !boxes[o + 1].isNaN() && !boxes[o + 3].isNaN()
            if (lineBreaks[i]) {
                // A break closes the line above it and belongs to no line itself, though the scan
                // has always seen it — a boxed one becomes a line of its own, so it still answers.
                if (start < i) bands += LineBand(start, i, top, bottom, skippable)
                if (boxed) bands += LineBand(i, i + 1, boxes[o + 1], boxes[o + 3], measurable)
                start = i + 1
                top = 0f
                bottom = 0f
                open = false
                skippable = true
            } else if (measurable) {
                top = if (open) min(top, boxes[o + 1]) else boxes[o + 1]
                bottom = if (open) max(bottom, boxes[o + 3]) else boxes[o + 3]
                open = true
            } else if (boxed) {
                skippable = false
            }
        }
        if (start < text.length) bands += LineBand(start, text.length, top, bottom, skippable)
        return bands
    }

    companion object {
        const val MAX_RECTS_PER_PAGE = 2000

        private val WHITESPACE_RUN = Regex("\\s+")

        /** NFKC-normalised so a typed "fi" finds the "ﬁ" ligature, whitespace runs collapsed to one space. */
        fun normalizeQuery(query: String): String =
            WHITESPACE_RUN.replace(Normalizer.normalize(query, Normalizer.Form.NFKC), " ")
    }
}
