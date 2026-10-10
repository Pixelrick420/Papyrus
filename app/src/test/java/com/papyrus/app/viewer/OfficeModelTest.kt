package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficeModelTest {

    private val bold = CharFormat(bold = true)
    private val italic = CharFormat(italic = true)

    // ---- RichTextBuilder ----

    @Test
    fun `builder records spans against the text as it grows`() {
        val builder = RichTextBuilder()
        builder.append("plain ")
        builder.append("bold", bold)
        builder.append(" tail")

        val snapshot = builder.snapshot()

        assertEquals("plain bold tail", snapshot.text)
        assertEquals(listOf(TextSpan(6, 10, bold)), snapshot.spans)
    }

    @Test
    fun `builder merges contiguous appends of one format but not across a gap or a change`() {
        val builder = RichTextBuilder()
        builder.append("a", bold)
        builder.append("b", bold)
        builder.append("c", italic)
        builder.append("d")
        builder.append("e", italic)

        assertEquals(
            listOf(TextSpan(0, 2, bold), TextSpan(2, 3, italic), TextSpan(4, 5, italic)),
            builder.snapshot().spans,
        )
    }

    @Test
    fun `trimming a snapshot cuts and re-bases its spans`() {
        val builder = RichTextBuilder()
        builder.append("\n\n")
        builder.append("body", bold)
        builder.append("\n")

        val snapshot = builder.snapshot { it == '\n' }

        assertEquals("body", snapshot.text)
        assertEquals(listOf(TextSpan(0, 4, bold)), snapshot.spans)
    }

    @Test
    fun `inserting at the start of a paragraph shifts later spans and grows a straddling one`() {
        val builder = RichTextBuilder()
        builder.append("one\n", bold)
        builder.append("two", bold)
        builder.append("!", italic)

        // "two" begins at 4, inside the bold span that also covers "one\n".
        builder.insert(4, "- ")

        val snapshot = builder.snapshot()
        assertEquals("one\n- two!", snapshot.text)
        assertEquals(listOf(TextSpan(0, 9, bold), TextSpan(9, 10, italic)), snapshot.spans)
    }

    @Test
    fun `appending rich text keeps its spans at their new offsets`() {
        val builder = RichTextBuilder()
        builder.append("host ")

        builder.appendRich("box", listOf(TextSpan(0, 3, bold)))

        assertEquals(listOf(TextSpan(5, 8, bold)), builder.snapshot().spans)
    }

    // ---- span and line helpers ----

    @Test
    fun `slicing keeps only the overlap, re-based, and drops spans outside`() {
        val spans = listOf(TextSpan(0, 3, bold), TextSpan(2, 8, italic), TextSpan(9, 12, bold))

        // The bold span ends at 3, so one character of it falls inside [2, 6); the italic one is cut at 6.
        assertEquals(listOf(TextSpan(0, 1, bold), TextSpan(0, 4, italic)), sliceSpans(spans, 2, 6))
        assertEquals(emptyList<TextSpan>(), sliceSpans(spans, 12, 20))
    }

    @Test
    fun `splitting lines drops empty ones and re-bases each line's spans`() {
        val text = "ab\n\ncd\n"
        val spans = listOf(TextSpan(1, 5, bold))

        assertEquals(
            // The span runs from "b" through both newlines into the "c" of "cd".
            listOf(LineText("ab", listOf(TextSpan(1, 2, bold))), LineText("cd", listOf(TextSpan(0, 1, bold)))),
            splitLines(text, spans),
        )
        assertEquals(text.split('\n').filter { it.isNotEmpty() }, splitLines(text, spans).map { it.text })
        assertEquals(emptyList<LineText>(), splitLines("", emptyList()))
    }

    // ---- list numbering ----

    @Test
    fun `list numbers format in every scheme Word offers`() {
        assertEquals("a", formatListNumber(1, "lowerLetter"))
        assertEquals("z", formatListNumber(26, "lowerLetter"))
        assertEquals("aa", formatListNumber(27, "lowerLetter"))
        assertEquals("AB", formatListNumber(28, "upperLetter"))
        assertEquals("iv", formatListNumber(4, "lowerRoman"))
        assertEquals("MCMXCIV", formatListNumber(1994, "upperRoman"))
        assertEquals("07", formatListNumber(7, "decimalZero"))
        assertEquals("12", formatListNumber(12, "decimal"))
        assertEquals("5", formatListNumber(5, "somethingNew"))
        // Out of range for roman numerals falls back to digits rather than inventing a symbol.
        assertEquals("4000", formatListNumber(4000, "lowerRoman"))
    }

    @Test
    fun `bullets from Symbol and Wingdings fall back to a plain bullet that steps down with the level`() {
        assertEquals("•", displayBullet("\uF0B7", 0))
        assertEquals("◦", displayBullet("\uF0B7", 1))
        assertEquals("▪", displayBullet("", 2))
        assertEquals("•", displayBullet(null, 3))
        assertEquals("◦", displayBullet("o", 0))
        assertEquals("–", displayBullet("–", 0))
        assertEquals("•", displayBullet("not one char", 0))
    }

    // ---- lengths, indentation, links ----

    @Test
    fun `lengths convert to twips and an unknown unit is rejected rather than guessed`() {
        assertEquals(1440, lengthToTwips("1in"))
        assertEquals(1440, lengthToTwips("72pt"))
        assertEquals(720, lengthToTwips("0.5in"))
        assertEquals(1440, lengthToTwips(" 96px "))
        assertNull(lengthToTwips("50%"))
        assertNull(lengthToTwips("wide"))
        assertNull(lengthToTwips(null))
    }

    @Test
    fun `indent is counted in quarter-inch steps and capped`() {
        assertEquals(0, indentLevel(null))
        assertEquals(0, indentLevel(200))
        assertEquals(2, indentLevel(720))
        assertEquals(6, indentLevel(100_000))
        assertEquals(0, indentLevel(-720))
    }

    @Test
    fun `only links that cannot run anything are kept`() {
        assertEquals("https://example.com/a?b=1", safeLink(" https://example.com/a?b=1 "))
        assertEquals("mailto:a@b.c", safeLink("mailto:a@b.c"))
        assertEquals("tel:+911234", safeLink("tel:+911234"))
        assertEquals("HTTP://EXAMPLE.COM", safeLink("HTTP://EXAMPLE.COM"))
        assertNull(safeLink("javascript:alert(1)"))
        assertNull(safeLink("intent://scan/#Intent;end"))
        assertNull(safeLink("file:///etc/passwd"))
        assertNull(safeLink("content://x/y"))
        assertNull(safeLink("#bookmark"))
        assertNull(safeLink(null))
    }

    @Test
    fun `colours parse from hex and the reset sentinel does not leak into a format`() {
        assertEquals(0xFF0000, parseHexColor("FF0000"))
        assertEquals(0x00FF7F, parseHexColor("#00ff7f"))
        assertNull(parseHexColor("auto"))
        assertNull(parseHexColor("FFF"))

        val props = RunProps().also {
            it.color = COLOR_AUTO
            it.background = COLOR_AUTO
        }
        assertEquals(CharFormat.PLAIN, props.toFormat(null))
    }

    @Test
    fun `merging layers lets the more specific say win only where it says anything`() {
        val base = RunProps().also {
            it.bold = true
            it.italic = true
        }
        val top = RunProps().also {
            it.bold = false
            it.underline = true
        }

        assertEquals(CharFormat(italic = true, underline = true), top.mergedOver(base).toFormat(null))
        // Neither layer is changed by merging.
        assertEquals(CharFormat(bold = true, italic = true), base.toFormat(null))
    }

    @Test
    fun `alignment words from both formats map onto three alignments and justify reads as start`() {
        assertEquals(BlockAlign.CENTER, alignOf("center"))
        assertEquals(BlockAlign.END, alignOf("right"))
        assertEquals(BlockAlign.END, alignOf("end"))
        assertEquals(BlockAlign.START, alignOf("both"))
        assertEquals(BlockAlign.START, alignOf("justify"))
        assertNull(alignOf("diagonal"))
    }
}
