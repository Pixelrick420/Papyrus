package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platform page geometry becomes the viewer's per-character model: box conversion, line splitting,
 * and the match and selection rectangles drawn on top of it.
 */
class PdfPlatformTextTest {

    private fun line(text: String, top: Float, bottom: Float, left: Float = 0f, right: Float) =
        PlatformLine(text, listOf(PageRect(left, top, right, bottom)))

    private fun page(vararg lines: PlatformLine, width: Float = 100f, height: Float = 100f) =
        PdfPlatformText.from(lines.toList(), width, height)

    @Test
    fun `one platform line becomes text with a box per glyph`() {
        val text = page(line("Hello", top = 0f, bottom = 10f, right = 100f), height = 10f)

        assertEquals("Hello", text.text)
        assertTrue(text.hasBoxes)
        assertEquals(0, text.nearestChar(0.01f, 0.5f)?.index)
        assertEquals(5, text.caretAt(0.99f, 0.5f))
    }

    @Test
    fun `whitespace takes no box but does not collapse the line`() {
        val text = page(line("a b", top = 0f, bottom = 10f, right = 30f), height = 10f)

        // The space is a third of the line, so the two letters keep their real places.
        val rect = text.selectionRects(0, 3).single()
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0.3f, rect.right, 0.0001f)
    }

    @Test
    fun `two platform lines become two lines of text and geometry`() {
        val text = page(
            line("ab", top = 0f, bottom = 5f, right = 10f),
            line("cd", top = 10f, bottom = 15f, right = 10f),
            width = 10f,
            height = 20f,
        )

        assertEquals("ab\ncd", text.text)
        assertEquals("ab\ncd", text.textBetween(0, 5))
        val rects = text.selectionRects(0, 5)
        assertEquals(2, rects.size)
        assertEquals(NormRect(0f, 0f, 1f, 0.25f), rects[0])
        assertEquals(NormRect(0f, 0.5f, 1f, 0.75f), rects[1])
    }

    @Test
    fun `a point on the second line lands on its characters`() {
        val text = page(
            line("ab", top = 0f, bottom = 5f, right = 10f),
            line("cd", top = 10f, bottom = 15f, right = 10f),
            width = 10f,
            height = 20f,
        )

        // y = 0.6 is the second line; x = 0.05 is the left half of its first glyph.
        assertEquals(3, text.nearestChar(0.05f, 0.6f)?.index)
        assertEquals(3, text.caretAt(0.05f, 0.6f))
    }

    @Test
    fun `a query never matches across the line break`() {
        val text = page(
            line("ab", top = 0f, bottom = 5f, right = 10f),
            line("cd", top = 10f, bottom = 15f, right = 10f),
            width = 10f,
            height = 20f,
        )

        // Line breaks are separators, not wrapping spaces: "b c" is not in "ab\ncd".
        assertEquals(0, text.matches("b c").count)
        assertEquals(1, text.matches("cd").count)
    }

    @Test
    fun `a page with no lines has text but no geometry`() {
        val text = PdfPlatformText.from(emptyList(), 100f, 100f)

        assertEquals("", text.text)
        assertFalse(text.hasBoxes)
        assertEquals(0, text.matches("anything").count)
        assertTrue(text.selectionRects(0, 10).isEmpty())
        assertNull(text.nearestChar(0.5f, 0.5f))
    }

    @Test
    fun `platform text says where it came from, so a selection can go back to the engine`() {
        val read = page(line("ab", top = 0f, bottom = 10f, right = 10f), height = 10f)

        assertTrue(read.fromPlatform)
        assertTrue(PdfPlatformText.from(emptyList(), 100f, 100f).fromPlatform)
    }

    @Test
    fun `text built from boxes says it did not come from the platform`() {
        assertFalse(PdfPageText.fromLines("ab", emptyList()).fromPlatform)
        assertTrue(PdfPageText.fromLines("ab", emptyList(), fromPlatform = true).fromPlatform)
    }

    @Test
    fun `a zero-width line gets no boxes`() {
        val text = page(line("ab", top = 0f, bottom = 10f, left = 5f, right = 5f), height = 10f)

        assertEquals("ab", text.text)
        assertNull(text.nearestChar(0.5f, 0.5f))
        assertNull(text.caretAt(0.5f, 0.5f))
        assertTrue(text.selectionRects(0, 2).isEmpty())
    }

    @Test
    fun `a line with several boxes unions them so every glyph stays hittable`() {
        val split = PlatformLine(
            "abcd",
            listOf(PageRect(0f, 0f, 20f, 10f), PageRect(50f, 0f, 100f, 10f)),
        )
        val text = PdfPlatformText.from(listOf(split), 100f, 10f)

        // The four glyphs share the union, so the far run's characters are still reachable.
        assertEquals(0, text.nearestChar(0.01f, 0.5f)?.index)
        assertEquals(4, text.caretAt(0.99f, 0.5f))
    }

    @Test
    fun `a platform hit becomes one group, wrapped hits keeping several boxes`() {
        val groups = PdfPlatformText.groups(
            listOf(
                PlatformMatch(0, listOf(PageRect(0f, 0f, 50f, 10f), PageRect(0f, 10f, 20f, 20f))),
                PlatformMatch(12, listOf(PageRect(30f, 0f, 60f, 10f))),
            ),
            100f,
            100f,
        )

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(1, groups[1].size)
        assertEquals(0.3f, groups[1][0].left, 0.0001f)
        assertEquals(0.6f, groups[1][0].right, 0.0001f)
    }

    @Test
    fun `hit rectangles stop at the page cap but later hits stay counted`() {
        val groups = PdfPlatformText.groups(
            listOf(
                PlatformMatch(0, listOf(PageRect(0f, 0f, 50f, 10f), PageRect(0f, 10f, 20f, 20f))),
                PlatformMatch(12, listOf(PageRect(30f, 0f, 60f, 10f))),
            ),
            100f,
            100f,
            limit = 2,
        )

        assertEquals(listOf(2, 0), groups.map { it.size })
    }

    @Test
    fun `a zero limit leaves every hit without rectangles`() {
        val groups = PdfPlatformText.groups(
            listOf(PlatformMatch(0, listOf(PageRect(0f, 0f, 50f, 10f)))),
            100f,
            100f,
            limit = 0,
        )

        assertEquals(listOf(0), groups.map { it.size })
    }

    @Test
    fun `a platform selection keeps its text and a box per line`() {
        val selection = PdfPlatformText.selection(
            listOf(
                PlatformLine("ab", listOf(PageRect(0f, 0f, 10f, 10f))),
                PlatformLine("cd", listOf(PageRect(0f, 20f, 10f, 30f))),
            ),
            10f,
            40f,
        )

        assertEquals("ab\ncd", selection.text)
        assertEquals(2, selection.rects.size)
        assertEquals(0f, selection.rects[0].top, 0.0001f)
        assertEquals(0.25f, selection.rects[0].bottom, 0.0001f)
        assertEquals(0.5f, selection.rects[1].top, 0.0001f)
    }

    @Test
    fun `rectangles are clamped to the page`() {
        assertEquals(
            NormRect(0f, 0f, 0.5f, 0.1f),
            PdfPlatformText.normRect(PageRect(-10f, -5f, 50f, 10f), 100f, 100f),
        )
    }

    @Test
    fun `an empty, inverted or non-finite rectangle is dropped`() {
        assertNull(PdfPlatformText.normRect(PageRect(10f, 10f, 10f, 20f), 100f, 100f))
        assertNull(PdfPlatformText.normRect(PageRect(30f, 10f, 20f, 20f), 100f, 100f))
        assertNull(PdfPlatformText.normRect(PageRect(150f, 0f, 200f, 20f), 100f, 100f))
        assertNull(PdfPlatformText.normRect(PageRect(Float.NaN, 0f, 10f, 10f), 100f, 100f))
        assertNull(PdfPlatformText.normRect(PageRect(0f, 0f, 10f, 10f), 0f, 100f))
    }

    @Test
    fun `a line without boxes keeps its characters unselectable`() {
        val text = PdfPageText.fromLines(
            "ab\ncd",
            listOf(emptyList(), listOf(NormRect(0f, 0.5f, 1f, 0.75f))),
        )

        assertEquals("ab\ncd", text.text)
        // The first line has no geometry, so a point on it can only reach the second line.
        assertEquals(3, text.nearestChar(0.01f, 0.1f)?.index)
        assertEquals(1, text.selectionRects(0, 5).size)
    }
}
