package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The footnote rewrite: a wrong number or a swallowed line changes what the reader is told. */
class MarkdownFootnotesTest {

    private fun transform(source: String) = MarkdownFootnotes.transform(source)

    @Test
    fun `a marker becomes a link and its definition a list entry that links back`() {
        val out = transform("Water boils at 100 C.[^a]\n\n[^a]: At sea level.\n")

        assertEquals(
            "Water boils at 100 C.[\u00B9](#fn-a)\n\n## Footnotes\n\n1. At sea level. [\u2191](#fnref-a)",
            out,
        )
    }

    @Test
    fun `a source with no footnotes comes back as the same string`() {
        val plain = "# Title\r\n\r\nJust text with a [link](https://example.com).\r\n"
        assertSame(plain, transform(plain))
    }

    @Test
    fun `a marker with no definition is left as typed and nothing else changes`() {
        val source = "Orphan[^missing] here.\n\nMore text.\n"
        assertEquals(source, transform(source))
    }

    @Test
    fun `entries are numbered in the order they are first referenced, not defined`() {
        val out = transform("First[^b] then[^a].\n\n[^a]: Defined first.\n[^b]: Defined second.\n")

        assertEquals(
            "First[\u00B9](#fn-b) then[\u00B2](#fn-a).\n\n## Footnotes\n\n" +
                "1. Defined second. [\u2191](#fnref-b)\n2. Defined first. [\u2191](#fnref-a)",
            out,
        )
    }

    @Test
    fun `a footnote cited twice keeps one number and one entry`() {
        val out = transform("One[^a] and again[^a].\n\n[^a]: Note.\n")

        assertEquals(
            "One[\u00B9](#fn-a) and again[\u00B9](#fn-a).\n\n## Footnotes\n\n1. Note. [\u2191](#fnref-a)",
            out,
        )
    }

    @Test
    fun `a second definition of an id is ignored and stays in the body as text`() {
        val out = transform("Cite[^a].\n\n[^a]: Keep this.\n\n[^a]: Not this.\n")

        assertTrue(out.contains("1. Keep this. [\u2191](#fnref-a)"))
        // Left where it was, and not read for markers: a link to itself would be wrong.
        assertTrue(out.contains("\n[^a]: Not this."))
        assertEquals(1, Regex("""\n\d+\. """).findAll(out).count())
    }

    @Test
    fun `indented lines continue a definition, blank lines between them included`() {
        val source = "Text[^n].\n\n" +
            "[^n]: First line\n" +
            "    second line\n" +
            "\n" +
            "    second paragraph\n" +
            "\n" +
            "Back to the body.\n"
        val out = transform(source)

        assertEquals(
            "Text[\u00B9](#fn-n).\n\n\nBack to the body.\n\n## Footnotes\n\n" +
                "1. First line\n   second line\n\n   second paragraph [\u2191](#fnref-n)",
            out,
        )
    }

    @Test
    fun `a definition ends at the first line that is not indented`() {
        val out = transform("A[^a] B[^b]\n\n[^a]: one\n  two\n[^b]: three\n")

        assertTrue(out.contains("1. one\n   two [\u2191](#fnref-a)\n2. three [\u2191](#fnref-b)"))
    }

    @Test
    fun `blank lines after a definition stay in the body`() {
        val out = transform("Para one[^a].\n\n[^a]: Note.\n\nPara two.\n")

        assertTrue(out.startsWith("Para one[\u00B9](#fn-a).\n\n\nPara two."))
    }

    @Test
    fun `numbers continue past nine into two digits and the continuation indent widens`() {
        val refs = (1..10).joinToString(" ") { "[^n$it]" }
        val defs = (1..10).joinToString("\n") { "[^n$it]: Note $it" } + "\n    more of ten\n"
        val out = transform("$refs\n\n$defs")

        // Only the tenth definition has a continuation, and it hangs under "10. ".
        assertTrue(out.contains("10. Note 10\n    more of ten [\u2191](#fnref-n10)"))
        assertTrue(out.contains("[\u00B9\u2070](#fn-n10)"))
    }

    @Test
    fun `markers and definitions inside a fenced block are left alone`() {
        val source = "Real[^a].\n\n```\n[^a]: not a definition\nand [^a] not a marker\n```\n\n[^a]: Real note.\n"
        val out = transform(source)

        assertTrue(out.contains("```\n[^a]: not a definition\nand [^a] not a marker\n```"))
        assertTrue(out.contains("Real[\u00B9](#fn-a)."))
        assertTrue(out.endsWith("1. Real note. [\u2191](#fnref-a)"))
    }

    @Test
    fun `a tilde fence and a longer fence are closed only by a matching one`() {
        val source = "[^a]: Note.\n\n~~~\n[^a] one\n~~~\n\n````\n```\n[^a] two\n```\n````\n\nAfter[^a].\n"
        val out = transform(source)

        // The inner three-backtick line does not close the four-backtick fence, so "two" is still code.
        assertTrue(out.contains("~~~\n[^a] one\n~~~"))
        assertTrue(out.contains("````\n```\n[^a] two\n```\n````"))
        assertTrue(out.contains("After[\u00B9](#fn-a)."))
    }

    @Test
    fun `a fence left open is closed before the list, so the list is not swallowed as code`() {
        val out = transform("A[^a]\n\n[^a]: Note.\n\n```\nnever closed\n")

        assertTrue(out.contains("```\nnever closed\n```\n\n## Footnotes\n\n1. Note."))
    }

    @Test
    fun `a marker in inline code is left alone and one beside it is not`() {
        val out = transform("Use `[^a]` for a marker[^a].\n\n[^a]: Note.\n")

        assertTrue(out.startsWith("Use `[^a]` for a marker[\u00B9](#fn-a)."))
    }

    @Test
    fun `a marker inside a double backtick span is left alone`() {
        val out = transform("Code ``a ` [^a]`` then[^a].\n\n[^a]: Note.\n")

        assertTrue(out.startsWith("Code ``a ` [^a]`` then[\u00B9](#fn-a)."))
    }

    @Test
    fun `an escaped marker is left as typed`() {
        val out = transform("Literal \\[^a] and real[^a].\n\n[^a]: Note.\n")

        assertTrue(out.startsWith("Literal \\[^a] and real[\u00B9](#fn-a)."))
    }

    @Test
    fun `a definition nothing refers to is still listed, after the referenced ones`() {
        val out = transform("Only[^b].\n\n[^a]: Unused.\n[^b]: Used.\n")

        assertEquals(
            "Only[\u00B9](#fn-b).\n\n## Footnotes\n\n1. Used. [\u2191](#fnref-b)\n2. Unused. [\u2191](#fnref-a)",
            out,
        )
    }

    @Test
    fun `a marker inside an entry is linked and the entry it names is listed after it`() {
        val out = transform("Body[^a].\n\n[^b]: Deep.\n[^a]: See also[^b].\n")

        assertEquals(
            "Body[\u00B9](#fn-a).\n\n## Footnotes\n\n" +
                "1. See also[\u00B2](#fn-b). [\u2191](#fnref-a)\n2. Deep. [\u2191](#fnref-b)",
            out,
        )
    }

    @Test
    fun `a body that is only definitions leaves just the list`() {
        assertEquals("## Footnotes\n\n1. Alone. [\u2191](#fnref-a)", transform("[^a]: Alone.\n"))
    }

    @Test
    fun `an entry whose text ends in a closing fence gets its way back on its own line`() {
        val out = transform("Code[^c].\n\n[^c]: Listing:\n    ```\n    x\n    ```\n")

        assertTrue(out.endsWith("   ```\n   [\u2191](#fnref-c)"))
    }

    @Test
    fun `the heading is whatever the caller passes`() {
        val out = MarkdownFootnotes.transform("A[^a]\n\n[^a]: B\n", heading = "Notes")
        assertTrue(out.contains("\n\n## Notes\n\n1. B"))
    }

    @Test
    fun `windows line endings are read and the result uses plain ones`() {
        val out = transform("A[^a]\r\n\r\n[^a]: B\r\n    c\r\n")

        assertEquals("A[\u00B9](#fn-a)\n\n## Footnotes\n\n1. B\n   c [\u2191](#fnref-a)", out)
    }

    @Test
    fun `a label with punctuation gets a safe anchor and still pairs up`() {
        val out = transform("A[^fn:1] B[^caf\u00E9]\n\n[^fn:1]: One\n[^caf\u00E9]: Two\n")

        assertTrue(out.startsWith("A[\u00B9](#fn-fn:1) B[\u00B2](#fn-caf_)"))
        assertTrue(out.contains("[\u2191](#fnref-caf_)"))
    }

    @Test
    fun `a definition indented four spaces is code, not a definition`() {
        val source = "Text[^a]\n\n    [^a]: in a code block\n"
        assertEquals(source, transform(source))
    }

    @Test
    fun `a footnote link is answered by the other half of the pair`() {
        assertEquals("#fnref-a", MarkdownFootnotes.counterpart("#fn-a"))
        assertEquals("#fn-a", MarkdownFootnotes.counterpart("#fnref-a"))
        assertEquals("#fnref-fn:1", MarkdownFootnotes.counterpart("#fn-fn:1"))
        assertNull(MarkdownFootnotes.counterpart("#heading"))
        assertNull(MarkdownFootnotes.counterpart("https://example.com/#fn-a"))
        assertNull(MarkdownFootnotes.counterpart("fn-a"))
    }

    @Test
    fun `only the anchors this class writes count as anchors`() {
        assertTrue(MarkdownFootnotes.isAnchor("#fn-a"))
        assertTrue(MarkdownFootnotes.isAnchor("#fnref-a"))
        assertTrue(!MarkdownFootnotes.isAnchor("#fnord"))
        assertTrue(!MarkdownFootnotes.isAnchor("https://example.com"))
    }

    @Test
    fun `a marker scrolls to its entry and the entry scrolls back to the first marker`() {
        val links = listOf(
            10 to "#fn-a",
            40 to "#fn-a",
            90 to "#fnref-a",
            55 to "https://example.com",
        )

        assertEquals(90, MarkdownFootnotes.target(links, "#fn-a"))
        // Cited at 10 and 40, one way back: it returns to the earlier of the two.
        assertEquals(10, MarkdownFootnotes.target(links, "#fnref-a"))
    }

    @Test
    fun `a link with no counterpart in the text, or no footnote link at all, goes nowhere`() {
        val links = listOf(10 to "#fn-a")

        assertNull(MarkdownFootnotes.target(links, "#fn-a"))
        assertNull(MarkdownFootnotes.target(links, "#elsewhere"))
        assertNull(MarkdownFootnotes.target(links, "https://example.com"))
    }

    @Test
    fun `find still matches the transformed text, the entry text included`() {
        val out = transform("Cite[^a] a needle.\n\n[^a]: Another needle here.\n")

        // Both hits are in the text the viewer will render, the second now below the body.
        val hits = Regex("needle").findAll(out).map { it.range.first }.toList()
        assertEquals(2, hits.size)
        assertTrue(hits[1] > out.indexOf("## Footnotes"))
    }
}
