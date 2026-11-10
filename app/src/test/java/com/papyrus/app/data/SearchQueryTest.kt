package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
        assertNull(SearchQuery.parse("!?.,;:---()[]{}'\"*/\\"))
    }

    private val alphabet: List<String> = listOf(
        "a", "b", "z", "Q", "0", "7", "é", "Ü", "ß", "日", "本", "Ω", "٣",
        " ", " ", "  ", "\t", "\n", "\r\n", "\u00A0", "\u3000", "\u0000",
        "\"", "'", "*", "-", "^", ":", "(", ")", "{", "}", "[", "]", "+", "~", "/", "\\", ".", ",", "!", "?",
        "AND", "OR", "NOT", "NEAR", "NEAR/3", "col:", "\"\"", "**", "--",
        "😀", "\uD83D", "\u0301", "\u200B",
    )

    private fun randomInput(random: Random): String =
        buildString { repeat(random.nextInt(0, 60)) { append(alphabet[random.nextInt(alphabet.size)]) } }

    private val inputs: List<String> = Random(20240607).let { random -> List(2_000) { randomInput(random) } }

    private val words = listOf("paper", "Scaling", "naïve", "日本", "2024", "ab", "Über", "x1")

    @Test
    fun `no input throws`() {
        for (input in inputs) SearchQuery.parse(input)
        // A very long run of one character, and of separators with a word hidden in them.
        SearchQuery.parse("a".repeat(200_000))
        SearchQuery.parse("(".repeat(200_000) + "word" + ")".repeat(200_000))
    }

    @Test
    fun `an expression is only quoted words, never an FTS operator`() {
        val phrase = "\"[\\p{L}\\p{N}]+\\*?\""
        val shape = Regex("^$phrase( $phrase)*$")
        for (input in inputs) {
            val parsed = SearchQuery.parse(input) ?: continue
            assertTrue("not a list of quoted words for ${input.escaped()}: ${parsed.expression}", shape.matches(parsed.expression))
            // Outside the quotes there is nothing but the single spaces between phrases.
            val outside = parsed.expression.replace(Regex(phrase), "").replace(" ", "")
            assertEquals("something outside the quotes for ${input.escaped()}", "", outside)
        }
    }

    @Test
    fun `terms are never more than the maximum and never empty`() {
        for (input in inputs + "w ".repeat(500)) {
            val parsed = SearchQuery.parse(input) ?: continue
            assertTrue(parsed.terms.isNotEmpty())
            assertTrue("${parsed.terms.size} terms for ${input.escaped()}", parsed.terms.size <= SearchQuery.MAX_TERMS)
            assertEquals(parsed.terms.size, parsed.expression.split(' ').size)
        }
    }

    @Test
    fun `every term is letters and digits only`() {
        val word = Regex("^[\\p{L}\\p{N}]+$")
        for (input in inputs) {
            for (term in SearchQuery.parse(input)?.terms.orEmpty()) {
                assertTrue("'$term' from ${input.escaped()}", word.matches(term))
            }
        }
    }

    @Test
    fun `a query with a word in it always parses`() {
        val letter = Regex("[\\p{L}\\p{N}]")
        for (input in inputs) {
            val parsed = SearchQuery.parse(input)
            if (letter.containsMatchIn(input)) assertNotNull(input.escaped(), parsed) else assertNull(input.escaped(), parsed)
        }
    }

    @Test
    fun `repeated whitespace and punctuation change nothing`() {
        val random = Random(7)
        val separators = listOf(" ", "  ", "\t", "\n\n", "-", "--", ", ", " (", ") ", "\"", "*", ":", "!?", " \u00A0 ")
        repeat(500) {
            val chosen = List(random.nextInt(1, 12)) { words[random.nextInt(words.size)] }
            val plain = chosen.joinToString(" ")
            val noisy = chosen.joinToString("") { it + separators[random.nextInt(separators.size)] }
            val padded = separators[random.nextInt(separators.size)] + noisy

            assertEquals(plain, SearchQuery.parse(plain), SearchQuery.parse(noisy))
            assertEquals(plain, SearchQuery.parse(plain), SearchQuery.parse(padded))
        }
    }

    @Test
    fun `parsing the terms again gives the same query`() {
        for (input in inputs) {
            val parsed = SearchQuery.parse(input) ?: continue
            assertEquals(input.escaped(), parsed, SearchQuery.parse(parsed.terms.joinToString(" ")))
        }
    }

    private fun String.escaped() = map { if (it.code < 32 || it.code > 126) "\\u%04x".format(it.code) else it.toString() }.joinToString("")
}
