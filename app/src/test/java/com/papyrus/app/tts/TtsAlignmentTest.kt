package com.papyrus.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Pure: finding the sentence and word being spoken, and finding them again in the text on screen. */
class TtsAlignmentTest {

    private val english = Locale.ENGLISH

    @Test
    fun `sentence bounds start every sentence and end at the length`() {
        val text = "Hello world. How are you? Fine."
        val bounds = TtsAlignment.sentenceBounds(text, english)
        assertEquals(0, bounds.first())
        assertEquals(text.length, bounds.last())
        assertEquals(4, bounds.size)
    }

    @Test
    fun `empty and single-sentence text still have one sentence`() {
        assertEquals(2, TtsAlignment.sentenceBounds("", english).size)
        val one = TtsAlignment.sentenceBounds("no full stop here", english)
        assertEquals(listOf(0, 17), one.toList())
    }

    @Test
    fun `the sentence at an offset leaves out the space after its full stop`() {
        val text = "Hello world. How are you? Fine."
        val bounds = TtsAlignment.sentenceBounds(text, english)
        val first = TtsAlignment.sentenceAt(text, bounds, 3)
        assertEquals("Hello world.", text.substring(first.first, first.last + 1))
        val second = TtsAlignment.sentenceAt(text, bounds, text.indexOf("are"))
        assertEquals("How are you?", text.substring(second.first, second.last + 1))
        val third = TtsAlignment.sentenceAt(text, bounds, text.length - 1)
        assertEquals("Fine.", text.substring(third.first, third.last + 1))
    }

    @Test
    fun `an offset past the end falls in the last sentence`() {
        val text = "One. Two."
        val bounds = TtsAlignment.sentenceBounds(text, english)
        val last = TtsAlignment.sentenceAt(text, bounds, 500)
        assertEquals("Two.", text.substring(last.first, last.last + 1))
    }

    @Test
    fun `a word ends at the next whitespace or the end of the text`() {
        assertEquals(5, TtsAlignment.wordEnd("hello world", 0))
        assertEquals(11, TtsAlignment.wordEnd("hello world", 6))
        assertEquals(5, TtsAlignment.wordEnd("hello", 0))
    }

    @Test
    fun `a word is found again across a line break and indentation`() {
        val source = "alpha beta\n    gamma delta\n\nnext"
        val unit = TtsDocumentSource.paragraphs(source).first()
        assertEquals("alpha beta gamma delta", unit.text)

        val gamma = unit.text.indexOf("gamma")
        val range = TtsAlignment.sourceRange(source, unit.text, unit.start, gamma until gamma + 5)!!
        assertEquals("gamma", source.substring(range.first, range.last + 1))
    }

    @Test
    fun `a unit later in the source is found from its own start`() {
        val source = "first paragraph\n\nsecond  one\nhere"
        val second = TtsDocumentSource.paragraphs(source)[1]
        assertEquals("second  one here", second.text)
        val word = second.text.indexOf("here")
        val range = TtsAlignment.sourceRange(source, second.text, second.start, word until word + 4)!!
        assertEquals("here", source.substring(range.first, range.last + 1))
    }

    @Test
    fun `carriage returns are walked as line breaks`() {
        val source = "one\r\n   two\r\nthree"
        val unit = TtsDocumentSource.paragraphs(source).first()
        val at = unit.text.indexOf("three")
        val range = TtsAlignment.sourceRange(source, unit.text, unit.start, at until at + 5)!!
        assertEquals("three", source.substring(range.first, range.last + 1))
    }

    @Test
    fun `a unit that kept its own line breaks maps one to one after its trimmed edges`() {
        // An Office block is trimmed, not collapsed: its text still holds the author's line break.
        val source = "  first line\nsecond  line "
        val unit = source.trim()
        val at = unit.indexOf("second")
        val range = TtsAlignment.sourceRange(source, unit, 0, at until at + 6)!!
        assertEquals("second", source.substring(range.first, range.last + 1))
        val tail = unit.indexOf("line", at)
        val last = TtsAlignment.sourceRange(source, unit, 0, tail until tail + 4)!!
        assertEquals("line", source.substring(last.first, last.last + 1))
    }

    @Test
    fun `a range of several words maps from its first to its last character`() {
        val source = "The quick\nbrown fox jumps."
        val unit = TtsDocumentSource.paragraphs(source).first()
        val from = unit.text.indexOf("quick")
        val to = unit.text.indexOf("fox") + 3
        val range = TtsAlignment.sourceRange(source, unit.text, unit.start, from until to)!!
        assertEquals("quick\nbrown fox", source.substring(range.first, range.last + 1))
    }

    @Test
    fun `nothing maps from an empty range, an empty source or a range outside the unit`() {
        val source = "alpha beta"
        assertNull(TtsAlignment.sourceRange(source, "alpha beta", 0, IntRange.EMPTY))
        assertNull(TtsAlignment.sourceRange("", "alpha beta", 0, 0..2))
        assertNull(TtsAlignment.sourceRange(source, "alpha beta", 0, 40..44))
        assertNull(TtsAlignment.sourceRange(source, "alpha beta", 0, -3..2))
    }

    @Test
    fun `a range running past the unit is clamped to it`() {
        val source = "alpha beta"
        val range = TtsAlignment.sourceRange(source, "alpha beta", 0, 6..99)!!
        assertEquals("beta", source.substring(range.first, range.last + 1))
    }

    @Test
    fun `every character of every paragraph maps back to itself`() {
        val sources = listOf(
            "plain\n\ntwo lines\nof text\n\n\n   indented\n\t\ttabbed line\n\nend",
            "crlf one\r\ncrlf two\r\n\r\nnext  paragraph\r\n   with indent",
            "  leading and trailing   \n\n lone cr\rjoined\r\rlast",
            "multiple   spaces   inside\nand\n\n\n\nmore blank lines",
            "Ünïcödé — dashes… and “quotes”\n    on a second line",
        )
        for (source in sources) {
            for (paragraph in TtsDocumentSource.paragraphs(source)) {
                for (i in paragraph.text.indices) {
                    if (paragraph.text[i].isWhitespace()) continue
                    val range = TtsAlignment.sourceRange(source, paragraph.text, paragraph.start, i..i)
                    assertTrue("character $i of \"${paragraph.text}\"", range != null)
                    assertEquals(paragraph.text[i], source[range!!.first])
                }
            }
        }
    }
}
