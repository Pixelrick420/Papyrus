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
}
