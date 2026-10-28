package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** findMatchRanges: where a match is, since an off-by-one highlights the wrong letters. */
class FindMatchingTest {

    @Test
    fun `ranges cover exactly the matched characters`() {
        val text = "one needle two needle"
        val ranges = findMatchRanges(text, "needle")

        assertEquals(listOf(4..9, 15..20), ranges)
        assertEquals(listOf("needle", "needle"), ranges.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `matching ignores case but the range indexes the original text`() {
        val text = "Needle NEEDLE needle"
        val ranges = findMatchRanges(text, "nEeDlE")
        assertEquals(3, ranges.size)
        assertEquals(listOf("Needle", "NEEDLE", "needle"), ranges.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `matches do not overlap`() {
        // The same convention as countOccurrences, so the highlights on screen and the counter agree.
        assertEquals(listOf(0..1, 2..3), findMatchRanges("aaaa", "aa"))
        assertEquals(listOf(0..1, 2..3), findMatchRanges("aaaaa", "aa"))
    }

    @Test
    fun `the number of ranges is the occurrence count`() {
        val text = "alpha beta alpha gamma alpha"
        assertEquals(countOccurrences(text, "alpha"), findMatchRanges(text, "alpha").size)
        assertEquals(countOccurrences("aaaaa", "aa"), findMatchRanges("aaaaa", "aa").size)
    }

    @Test
    fun `an empty query or a query longer than the text matches nothing`() {
        assertTrue(findMatchRanges("alpha", "").isEmpty())
        assertTrue(findMatchRanges("ab", "abcdef").isEmpty())
        assertTrue(findMatchRanges("", "a").isEmpty())
    }

    @Test
    fun `a letter whose lower case is longer does not shift later matches`() {
        // Capital dotted I lower-cases to two chars, so matching is indexOf, not lowercase().
        val text = "\u0130stanbul needle"
        assertEquals(text.length + 1, text.lowercase().length)
        val ranges = findMatchRanges(text, "needle")
        assertEquals(1, ranges.size)
        assertEquals("needle", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun `a query with spaces in it is matched literally`() {
        val text = "the quick brown fox"
        assertEquals(listOf(4..14), findMatchRanges(text, "quick brown"))
    }

    @Test
    fun `searching for unrendered link destination highlights rendered link text`() {
        val theme = io.noties.markwon.core.MarkwonTheme.emptyBuilder().build()
        val linkSpan = io.noties.markwon.core.spans.LinkSpan(theme, "actual-link") { _, _ -> }
        val spanned = FakeSpanned("before example-link-text after", listOf(Triple(linkSpan, 7, 24)))
        val ranges = findMatchRanges(spanned, "actual-link")
        assertEquals(listOf(7..23), ranges)
        assertEquals(1, countOccurrences(spanned, "actual-link"))
    }

    @Test
    fun `spans with unrendered link destination compose with direct text matches`() {
        val theme = io.noties.markwon.core.MarkwonTheme.emptyBuilder().build()
        val linkSpan = io.noties.markwon.core.spans.LinkSpan(theme, "https://example.com/actual-link") { _, _ -> }
        val spanned = FakeSpanned("actual first, then example-link-text and done", listOf(Triple(linkSpan, 19, 36)))
        val ranges = findMatchRanges(spanned, "actual")
        assertEquals(listOf(0..5, 19..35), ranges)
        assertEquals(2, countOccurrences(spanned, "actual"))
    }

    @Test
    fun `a footnote anchor is not searchable text, so a label in a query does not light up its markers`() {
        val theme = io.noties.markwon.core.MarkwonTheme.emptyBuilder().build()
        val marker = io.noties.markwon.core.spans.LinkSpan(theme, "#fn-note") { _, _ -> }
        val back = io.noties.markwon.core.spans.LinkSpan(theme, "#fnref-note") { _, _ -> }
        val spanned = FakeSpanned("Text\u00B9 and a note.\n\n1. Body \u2191", listOf(Triple(marker, 4, 5), Triple(back, 27, 28)))

        // "note" is in the text once, and in both anchors, which are not the author's words.
        assertEquals(listOf(12..15), findMatchRanges(spanned, "note"))
        assertEquals(1, countOccurrences(spanned, "note"))
        assertEquals(0, countOccurrences(spanned, "fnref"))
    }

    @Test
    fun `an ordinary link to a fragment is still searchable by its destination`() {
        val theme = io.noties.markwon.core.MarkwonTheme.emptyBuilder().build()
        val link = io.noties.markwon.core.spans.LinkSpan(theme, "#heading-note") { _, _ -> }
        val spanned = FakeSpanned("see here", listOf(Triple(link, 4, 8)))

        assertEquals(listOf(4..7), findMatchRanges(spanned, "heading-note"))
    }

    @Test
    fun `office text spans with link match destination and highlight text`() {
        val span = com.papyrus.app.viewer.TextSpan(7, 24, com.papyrus.app.viewer.CharFormat(link = "https://example.com/target"))
        val text = "before example-link-text after"
        val ranges = findMatchRanges(text, listOf(span), "target")
        assertEquals(listOf(7..23), ranges)
        assertEquals(1, countOccurrences(text, listOf(span), "target"))
    }
}

private class FakeSpanned(
    private val text: String,
    private val spans: List<Triple<Any, Int, Int>> = emptyList(),
) : android.text.Spanned, CharSequence by text {
    override fun toString(): String = text
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>?): Array<T> {
        val matched = spans.filter { (span, s, e) ->
            (type == null || type.isInstance(span)) && s <= end && e >= start
        }.map { it.first as T }
        val array = java.lang.reflect.Array.newInstance(type ?: Any::class.java, matched.size) as Array<T>
        for (i in matched.indices) array[i] = matched[i]
        return array
    }
    override fun getSpanStart(tag: Any?): Int = spans.firstOrNull { it.first === tag }?.second ?: -1
    override fun getSpanEnd(tag: Any?): Int = spans.firstOrNull { it.first === tag }?.third ?: -1
    override fun getSpanFlags(tag: Any?): Int = 0
    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int = limit
}
