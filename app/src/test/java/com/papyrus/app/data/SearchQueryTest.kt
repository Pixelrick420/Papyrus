package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What reaches FTS4 as a MATCH expression, and what must never reach it: the reader's text is
 * untrusted input, and FTS4 throws on syntax it does not understand.
 */
class SearchQueryTest {

    private fun expression(input: String) = SearchQuery.parse(input)?.expression

    private fun terms(input: String) = SearchQuery.parse(input)?.terms

    @Test
    fun `words are quoted and join with FTS4's implicit AND`() {
        assertEquals("\"scaling*\" \"paper*\"", expression("scaling paper"))
        assertEquals(listOf("scaling", "paper"), terms("scaling paper"))
    }

    @Test
    fun `the prefix asterisk goes inside the quotes, where FTS4 reads it`() {
        // `"scal"*` means an exact match; FTS4 only honours the wildcard before the closing quote.
        assertEquals("\"scal*\"", expression("scal"))
    }

    @Test
    fun `the shortest prefix is two characters, where the index's prefix tables start`() {
        assertEquals(2, SearchQuery.MIN_PREFIX_LENGTH)
        assertEquals("\"ab*\"", expression("ab"))
        assertEquals("\"a\"", expression("a"))
    }

    @Test
    fun `a single-letter word is dropped when a longer one is present`() {
        assertEquals(listOf("paper"), terms("a paper"))
        assertEquals("\"paper*\"", expression("a paper"))
    }

    @Test
    fun `a query made only of single-letter words is still honoured, without a prefix`() {
        assertEquals("\"I\" \"a\"", expression("I a"))
    }

    @Test
    fun `FTS4 operators typed by the reader stay literal words`() {
        // Inside a quoted phrase FTS4 treats these as text, so no operator reaches the parser.
        assertEquals("\"AND*\" \"OR*\" \"NOT*\" \"NEAR*\"", expression("AND OR NOT NEAR"))
    }

    @Test
    fun `punctuation separates words and is dropped before quoting`() {
        assertEquals(listOf("paper", "trail", "draft"), terms("paper-trail, (draft)!"))
        assertEquals("\"paper*\" \"trail*\" \"draft*\"", expression("paper-trail, (draft)!"))
    }

    @Test
    fun `a typed wildcard is dropped rather than passed through`() {
        // One asterisk is added by us, inside the quotes; the reader's is not a query feature.
        assertEquals("\"scal*\"", expression("scal*"))
    }

    @Test
    fun `a typed quote cannot close the phrase`() {
        assertEquals(listOf("say", "hi"), terms("say \"hi\""))
        assertEquals("\"say*\" \"hi*\"", expression("say \"hi\""))
    }

    @Test
    fun `letters outside ASCII survive, so a non-English word is searchable`() {
        assertEquals(listOf("Grüße", "naïve"), terms("Grüße naïve"))
    }

    @Test
    fun `digits are words, so a year is searchable`() {
        assertEquals("\"2024*\"", expression("2024"))
    }

    @Test
    fun `words keep the order they were typed in`() {
        assertEquals(listOf("beta", "alpha"), terms("beta alpha"))
    }

    @Test
    fun `a pasted paragraph is cut to the maximum term count`() {
        val words = (1..12).map { "word$it" }

        val parsed = SearchQuery.parse(words.joinToString(" "))

        assertEquals(SearchQuery.MAX_TERMS, parsed?.terms?.size)
        assertEquals(SearchQuery.MAX_TERMS, parsed?.expression?.split(' ')?.size)
        assertEquals(words.take(SearchQuery.MAX_TERMS), parsed?.terms)
    }

    @Test
    fun `nothing searchable parses to null`() {
        assertNull(SearchQuery.parse(""))
        assertNull(SearchQuery.parse("   "))
        assertNull(SearchQuery.parse("!?.,;:-–—()[]{}'\"*/\\"))
    }
}
