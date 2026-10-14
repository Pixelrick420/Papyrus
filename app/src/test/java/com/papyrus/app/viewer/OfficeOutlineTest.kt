package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outline is a list of places to jump to, so a wrong index sends the reader to the wrong paragraph. */
class OfficeOutlineTest {

    private fun h(text: String, level: Int) = OfficeBlock.Heading(text, level)

    private fun p(text: String) = OfficeBlock.Paragraph(text)

    @Test
    fun `headings are listed with the index of their own block`() {
        val blocks = listOf(h("Intro", 1), p("body"), p("body"), h("Method", 2), OfficeBlock.Divider, h("Results", 1))

        assertEquals(
            listOf(OutlineEntry(0, 0, "Intro"), OutlineEntry(3, 1, "Method"), OutlineEntry(5, 0, "Results")),
            officeOutline(blocks),
        )
    }

    @Test
    fun `a document with no headings has no outline`() {
        assertTrue(officeOutline(listOf(p("a"), p("b"), OfficeBlock.Divider)).isEmpty())
        assertTrue(officeOutline(emptyList()).isEmpty())
    }

    @Test
    fun `depth is relative to the shallowest heading the document uses`() {
        // Opens at Heading 2 and never uses 1: nothing should be indented for a level that is absent.
        val blocks = listOf(h("A", 2), h("B", 3), h("C", 2))

        assertEquals(listOf(0, 1, 0), officeOutline(blocks).map { it.depth })
    }

    @Test
    fun `a heading that wraps or carries a break shows as one line`() {
        val blocks = listOf(h("Part one\n  of   the report ", 1))

        assertEquals("Part one of the report", officeOutline(blocks).single().title)
    }

    @Test
    fun `a heading with no visible words is skipped but later indices stay true`() {
        val blocks = listOf(h("  \n ", 1), p("body"), h("Real", 1))

        assertEquals(listOf(OutlineEntry(2, 0, "Real")), officeOutline(blocks))
    }

    @Test
    fun `a very long heading is cut and very deep levels share the last indent`() {
        val blocks = listOf(h("x".repeat(500), 1), h("deep", 6), h("deeper", 6))

        val outline = officeOutline(blocks)

        assertEquals(200, outline[0].title.length)
        assertEquals(5, outline[1].depth)
        assertEquals(5, outline[2].depth)
    }

    @Test
    fun `an outline taken from a parsed docx jumps to the heading's own block`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>One</w:t></w:r></w:p>
                   <w:p><w:r><w:t>text</w:t></w:r></w:p>
                   <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Two</w:t></w:r></w:p>""",
            ),
        )
        val blocks = OfficeTextExtractor(mediaDir = null, newParserFactory = OfficeFixtures::parser)
            .extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

        val outline = officeOutline(blocks)

        assertEquals(listOf("One", "Two"), outline.map { it.title })
        // Every entry's index really is that heading: this is what makes the jump land.
        outline.forEach { assertEquals(it.title, (blocks[it.index] as OfficeBlock.Heading).text) }
    }
}
