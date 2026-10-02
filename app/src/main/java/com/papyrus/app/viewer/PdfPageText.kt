package com.papyrus.app.viewer

import java.text.Normalizer
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

/** A rectangle in page-relative units: 0..1 across and down, origin at the page's top-left. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

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

    /** One rect per matched run per line; empty when no match or no boxes, so [contains] tells the two apart. */
    fun matchRects(query: String, limit: Int = MAX_RECTS_PER_PAGE): List<NormRect> {
        val needle = normalizeQuery(query)
        if (needle.isEmpty() || boxes.isEmpty()) return emptyList()
        val rects = ArrayList<NormRect>()
        var from = 0
        while (rects.size < limit) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            appendLineRects(at, at + needle.length, rects, limit)
            from = at + needle.length
        }
        return rects
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

    companion object {
        const val MAX_RECTS_PER_PAGE = 2000

        private val WHITESPACE_RUN = Regex("\\s+")

        /** NFKC-normalised so a typed "fi" finds the "ﬁ" ligature, whitespace runs collapsed to one space. */
        fun normalizeQuery(query: String): String =
            WHITESPACE_RUN.replace(Normalizer.normalize(query, Normalizer.Form.NFKC), " ")
    }
}
