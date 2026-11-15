package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextShaperTest {

    private val shaper = TextShaper()

    @Test
    fun `flatten keeps the text, one style per character and the break offsets`() {
        val first = TextRunProperties(latinTypeface = "Arial")
        val second = TextRunProperties(latinTypeface = "Calibri")
        val paragraph = TextParagraph(
            runs = listOf(
                TextRun.Text("ab", first),
                TextRun.Break(first),
                TextRun.Field("slidenum", "2", second),
            ),
        )

        val text = shaper.flatten(paragraph)

        assertEquals("ab2", text.text)
        assertEquals(3, text.styles.size)
        assertSame(first, text.styles[0])
        assertSame(second, text.styles[2])
        assertEquals(listOf(2), text.breaks)
    }

    @Test
    fun `a stored break at the end keeps its offset`() {
        val paragraph = TextParagraph(
            runs = listOf(TextRun.Text("ab", TextRunProperties()), TextRun.Break(TextRunProperties())),
        )

        assertEquals(listOf(2), shaper.flatten(paragraph).breaks)
    }

    @Test
    fun `a resolved font change splits the run`() {
        val paragraph = TextParagraph(
            runs = listOf(
                TextRun.Text("ab", TextRunProperties(latinTypeface = "Arial")),
                TextRun.Text("cd", TextRunProperties(latinTypeface = "Calibri")),
            ),
        )

        val runs = shaper.shape(shaper.flatten(paragraph), baseRtl = false)

        assertEquals(2, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[1].start)
        assertEquals("Calibri", runs[1].properties.latinTypeface)
    }

    @Test
    fun `a style change alone splits the run`() {
        val first = TextRunProperties(sizePt = 18.0)
        val second = TextRunProperties(sizePt = 18.0)
        val paragraph = TextParagraph(
            runs = listOf(TextRun.Text("ab", first), TextRun.Text("cd", second)),
        )

        val runs = shaper.shape(shaper.flatten(paragraph), baseRtl = false)

        assertEquals(2, runs.size)
        assertSame(first, runs[0].properties)
        assertSame(second, runs[1].properties)
    }

    @Test
    fun `a character the face cannot draw is split out on both sides`() {
        val paragraph = TextParagraph(runs = listOf(TextRun.Text("a\uD83D\uDE00b", TextRunProperties())))
        val missing = 0x1F600

        val runs = shaper.shape(shaper.flatten(paragraph), baseRtl = false) { _, codePoint ->
            codePoint != missing
        }

        assertEquals(3, runs.size)
        assertEquals(1, runs[1].start)
        assertEquals(3, runs[1].end)
        assertEquals(3, runs[2].start)
    }

    @Test
    fun `a shaped run takes the bidi level of its direction`() {
        val paragraph = TextParagraph(runs = listOf(TextRun.Text("abc אבג", TextRunProperties())))

        val runs = shaper.shape(shaper.flatten(paragraph), baseRtl = false)

        assertFalse(runs.first().rtl)
        assertTrue(runs.any { it.rtl })
        assertEquals(7, runs.sumOf { it.end - it.start })
    }

    @Test
    fun `an empty paragraph has no shaped runs`() {
        assertTrue(shaper.shape(shaper.flatten(TextParagraph()), baseRtl = false).isEmpty())
    }

    @Test
    fun `a surrogate pair stays whole inside one run`() {
        val paragraph = TextParagraph(runs = listOf(TextRun.Text("a\uD83D\uDE00", TextRunProperties())))

        val runs = shaper.shape(shaper.flatten(paragraph), baseRtl = false)

        assertEquals(1, runs.size)
        assertEquals(3, runs.single().end)
    }
}
