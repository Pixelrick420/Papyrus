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

    /** Match count, including ones whose boxes were dropped; non-overlapping like [matchGroups]. */
    fun countMatches(query: String): Int {
        val needle = normalizeQuery(query)
        if (needle.isEmpty()) return 0
        return matchStarts(needle).count()
    }

    /**
     * One entry per match in order, each holding that match's rectangles (one per line it covers);
     * a match with no drawable glyph keeps an empty entry. Stops once [limit] rectangles exist.
     */
    fun matchGroups(query: String, limit: Int = MAX_RECTS_PER_PAGE): List<List<NormRect>> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty() || boxes.isEmpty()) return emptyList()
        val groups = ArrayList<List<NormRect>>()
        var collected = 0
        for (at in matchStarts(needle)) {
            if (collected >= limit) break
            val rects = ArrayList<NormRect>()
            appendLineRects(at, at + needle.length, rects, limit - collected)
            groups += rects
            collected += rects.size
        }
        return groups
    }

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
        for (i in text.indices) {
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

    /** Start of each non-overlapping match: the next search resumes after the whole match. */
    private fun matchStarts(needle: String): Sequence<Int> = sequence {
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return@sequence
            yield(at)
            from = at + needle.length
        }
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

    companion object {
        const val MAX_RECTS_PER_PAGE = 2000

        private val WHITESPACE_RUN = Regex("\\s+")

        /** NFKC-normalised so a typed "fi" finds the "ﬁ" ligature, whitespace runs collapsed to one space. */
        fun normalizeQuery(query: String): String =
            WHITESPACE_RUN.replace(Normalizer.normalize(query, Normalizer.Form.NFKC), " ")
    }
}
