package com.papyrus.app.tts

import java.text.BreakIterator
import java.util.Locale

object TtsAlignment {

    // Sentences follow the platform's rules, so an abbreviation can split one early; this only shades a passage, never changes what is spoken.
    fun sentenceBounds(text: String, locale: Locale = Locale.getDefault()): IntArray {
        if (text.isEmpty()) return intArrayOf(0, 0)
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)
        val bounds = ArrayList<Int>()
        var at = iterator.first()
        while (at != BreakIterator.DONE) {
            bounds.add(at)
            at = iterator.next()
        }
        if (bounds.last() != text.length) bounds.add(text.length)
        if (bounds.size < 2) bounds.add(text.length)
        return bounds.toIntArray()
    }

    // The trailing space is left out so a shaded sentence never reaches past its full stop into the next line.
    fun sentenceAt(text: String, bounds: IntArray, offset: Int): IntRange {
        val last = bounds.size - 2
        var low = 0
        var high = last
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (bounds[mid] <= offset) low = mid else high = mid - 1
        }
        val start = bounds[low]
        var end = bounds[low + 1]
        while (end > start + 1 && text[end - 1].isWhitespace()) end--
        return start until end
    }

    fun wordEnd(text: String, start: Int): Int {
        var end = start.coerceAtLeast(0)
        while (end < text.length && !text[end].isWhitespace()) end++
        return end
    }

    // A unit is its source with whitespace changed (lines trimmed and joined by one space), so the two are walked together.
    fun sourceRange(source: CharSequence, unitText: String, unitStart: Int, range: IntRange): IntRange? {
        if (source.isEmpty() || unitText.isEmpty() || range.isEmpty()) return null
        val first = range.first
        val last = minOf(range.last, unitText.lastIndex)
        if (first < 0 || first > last) return null

        var from = -1
        var to = -1
        var at = unitStart.coerceAtLeast(0)
        for (i in 0..last) {
            val c = unitText[i]
            val mapped: Int
            if (c.isWhitespace()) {
                mapped = at.coerceAtMost(source.length - 1)
                if (at < source.length && source[at].isWhitespace()) {
                    at++
                    // One space of the unit stands for the whole gap, unless the unit's own next
                    // character is also whitespace and wants its share.
                    if (i + 1 < unitText.length && !unitText[i + 1].isWhitespace()) {
                        while (at < source.length && source[at].isWhitespace()) at++
                    }
                }
            } else {
                // Indentation, or the line break before the first word, has no counterpart in the unit.
                while (at < source.length && source[at].isWhitespace()) at++
                mapped = at.coerceAtMost(source.length - 1)
                at++
            }
            if (i == first) from = mapped
            if (i == last) to = mapped
        }
        return if (from < 0 || to < from) null else from..to
    }
}
