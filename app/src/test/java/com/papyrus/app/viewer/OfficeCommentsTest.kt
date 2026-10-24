package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reviewer-written text that must stay readable and never leak into the body. */
class OfficeCommentsTest {

    private fun extractDocx(bytes: ByteArray) = OfficeTextExtractor(
        mediaDir = null,
        newParserFactory = OfficeFixtures::parser,
    ).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

    private fun extractOdt(bytes: ByteArray) = OfficeTextExtractor(
        mediaDir = null,
        newParserFactory = OfficeFixtures::parser,
    ).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.ODT)

    private fun comments(vararg entries: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">${entries.joinToString("")}</w:comments>"""

    private fun comment(id: Int, author: String, date: String, text: String) =
        """<w:comment w:id="$id" w:author="$author" w:date="$date"><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:comment>"""

    private fun docxWith(body: String, commentsXml: String?, footnotesXml: String? = null) = OfficeFixtures.docx(
        OfficeFixtures.docxBody(body),
        rawEntries = buildMap {
            commentsXml?.let { put("word/comments.xml", it.toByteArray()) }
            footnotesXml?.let { put("word/footnotes.xml", it.toByteArray()) }
        },
    )

    @Test
    fun `docx comment is marked in the text and listed after the body with its author and date`() {
        val bytes = docxWith(
            """<w:p><w:r><w:t xml:space="preserve">Check </w:t></w:r>
               <w:commentRangeStart w:id="0"/><w:r><w:t>this number</w:t></w:r><w:commentRangeEnd w:id="0"/>
               <w:r><w:commentReference w:id="0"/></w:r><w:r><w:t xml:space="preserve"> twice.</w:t></w:r></w:p>""",
            comments(comment(0, "Alice", "2024-05-01T10:15:00Z", "Is it right?")),
        )

        val blocks = extractDocx(bytes)

        val paragraph = blocks[0] as OfficeBlock.Paragraph
        assertEquals("Check this numberC1 twice.", paragraph.text)
        // The commented words sit under the range highlight; the marker is raised, with its own fill.
        assertEquals(
            listOf(
                TextSpan(6, 17, CharFormat(background = COMMENT_RANGE_FILL)),
                TextSpan(17, 19, COMMENT_MARKER_FORMAT),
            ),
            paragraph.spans,
        )
        assertEquals(OfficeBlock.Divider, blocks[1])
        assertEquals(OfficeBlock.Note("C1", "Is it right?", byline = "Alice \u00B7 2024-05-01 10:15"), blocks[2])
        assertEquals(3, blocks.size)
    }

    @Test
    fun `docx comments are numbered in the order they are referenced, not stored`() {
        // comments.xml lists id 1 first; the body meets id 0 first, so that one is C1.
        val bytes = docxWith(
            """<w:p><w:r><w:t>A</w:t></w:r><w:r><w:commentReference w:id="0"/></w:r>
               <w:r><w:t>B</w:t></w:r><w:r><w:commentReference w:id="1"/></w:r></w:p>""",
            comments(comment(1, "Bob", "2024-05-02T09:00:00Z", "second"), comment(0, "Alice", "2024-05-01T09:00:00Z", "first")),
        )

        val notes = extractDocx(bytes).filterIsInstance<OfficeBlock.Note>()

        assertEquals(listOf("C1" to "first", "C2" to "second"), notes.map { it.label to it.text })
        assertEquals(listOf("Alice \u00B7 2024-05-01 09:00", "Bob \u00B7 2024-05-02 09:00"), notes.map { it.byline })
    }

    @Test
    fun `docx comment text never joins the body, and a reply is its own comment`() {
        val bytes = docxWith(
            """<w:p><w:r><w:t>Body</w:t></w:r><w:r><w:commentReference w:id="0"/></w:r><w:r><w:commentReference w:id="1"/></w:r></w:p>""",
            comments(comment(0, "Alice", "2024-05-01T10:00:00Z", "Question?"), comment(1, "Bob", "2024-05-01T11:00:00Z", "Answer.")),
        )

        val blocks = extractDocx(bytes)

        assertEquals("BodyC1C2", (blocks[0] as OfficeBlock.Paragraph).text)
        assertEquals(listOf("Question?", "Answer."), blocks.filterIsInstance<OfficeBlock.Note>().map { it.text })
    }

    @Test
    fun `docx footnotes and comments each sit under their own rule`() {
        val footnotes = """<w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:footnote w:id="1"><w:p><w:r><w:t>Source.</w:t></w:r></w:p></w:footnote></w:footnotes>"""
        val bytes = docxWith(
            """<w:p><w:r><w:t>Claim</w:t></w:r><w:r><w:footnoteReference w:id="1"/></w:r>
               <w:r><w:commentReference w:id="0"/></w:r></w:p>""",
            comments(comment(0, "Alice", "2024-05-01T10:00:00Z", "Cite more.")),
            footnotesXml = footnotes,
        )

        val blocks = extractDocx(bytes)

        assertEquals(
            listOf<OfficeBlock>(
                blocks[0],
                OfficeBlock.Divider,
                OfficeBlock.Note("1", "Source."),
                OfficeBlock.Divider,
                OfficeBlock.Note("C1", "Cite more.", byline = "Alice \u00B7 2024-05-01 10:00"),
            ),
            blocks,
        )
    }

    @Test
    fun `docx highlight the author chose is kept under a comment range`() {
        val bytes = docxWith(
            """<w:p><w:commentRangeStart w:id="0"/>
               <w:r><w:rPr><w:highlight w:val="green"/></w:rPr><w:t>marked</w:t></w:r>
               <w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p>""",
            comments(comment(0, "Alice", "2024-05-01T10:00:00Z", "ok")),
        )

        val paragraph = extractDocx(bytes)[0] as OfficeBlock.Paragraph

        // The word keeps the author's green, not the comment's gold.
        assertEquals(highlightColor("green"), paragraph.spans.first().format.background)
        assertEquals(TextSpan(0, 6, CharFormat(background = highlightColor("green"))), paragraph.spans.first())
    }

    @Test
    fun `docx text after a closed comment range is not highlighted`() {
        val bytes = docxWith(
            """<w:p><w:commentRangeStart w:id="0"/><w:r><w:t>in</w:t></w:r><w:commentRangeEnd w:id="0"/>
               <w:r><w:t>out</w:t></w:r></w:p>""",
            comments(comment(0, "Alice", "2024-05-01T10:00:00Z", "ok")),
        )

        val paragraph = extractDocx(bytes)[0] as OfficeBlock.Paragraph

        assertEquals("inout", paragraph.text)
        assertEquals(listOf(TextSpan(0, 2, CharFormat(background = COMMENT_RANGE_FILL))), paragraph.spans)
    }

    @Test
    fun `docx without a comments part keeps the body and does not crash`() {
        val bytes = docxWith(
            """<w:p><w:r><w:t>Body</w:t></w:r><w:r><w:commentReference w:id="0"/></w:r></w:p>""",
            commentsXml = null,
        )

        val blocks = extractDocx(bytes)

        // The marker stays, as a footnote's does when its part is missing; there is just nothing to list.
        assertEquals(1, blocks.size)
        assertEquals("BodyC1", (blocks[0] as OfficeBlock.Paragraph).text)
    }

    @Test
    fun `docx comment in a table cell is marked inside the cell`() {
        val bytes = docxWith(
            """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r><w:r><w:commentReference w:id="0"/></w:r></w:p></w:tc></w:tr></w:tbl>""",
            comments(comment(0, "Alice", "2024-05-01T10:00:00Z", "About the cell.")),
        )

        val blocks = extractDocx(bytes)

        assertEquals("CellC1", (blocks[0] as OfficeBlock.Table).rows[0][0].text)
        assertEquals("About the cell.", (blocks.last() as OfficeBlock.Note).text)
    }

    @Test
    fun `odt comment on a range is marked where the range ends`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """<text:p>Check <office:annotation office:name="a1"><dc:creator>Alice</dc:creator>
                   <dc:date>2024-05-01T10:00:00</dc:date><text:p>Is it right?</text:p></office:annotation>this number<office:annotation-end office:name="a1"/> twice.</text:p>""",
            ),
        )

        val blocks = extractOdt(bytes)

        val paragraph = blocks[0] as OfficeBlock.Paragraph
        assertEquals("Check this numberC1 twice.", paragraph.text)
        assertEquals(
            listOf(
                TextSpan(6, 17, CharFormat(background = COMMENT_RANGE_FILL)),
                TextSpan(17, 19, COMMENT_MARKER_FORMAT),
            ),
            paragraph.spans,
        )
        assertEquals(OfficeBlock.Note("C1", "Is it right?", byline = "Alice \u00B7 2024-05-01 10:00"), blocks.last())
    }

    @Test
    fun `odt comment with several paragraphs keeps them on separate lines`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """<text:p>Text<office:annotation><dc:creator>Alice</dc:creator>
                   <text:p>First.</text:p><text:p>Second.</text:p></office:annotation></text:p>""",
            ),
        )

        val note = extractOdt(bytes).last() as OfficeBlock.Note

        assertEquals("First.\nSecond.", note.text)
        assertEquals("Alice", note.byline)
    }

    @Test
    fun `odt comment with no words leaves no marker`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """<text:p>Text<office:annotation><dc:creator>Alice</dc:creator></office:annotation> more</text:p>""",
            ),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Text more")), extractOdt(bytes))
    }

    @Test
    fun `odt comment inside a footnote leaves the footnote whole`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """<text:p>Claim<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation>
                   <text:note-body><text:p>Source<office:annotation><dc:creator>Bob</dc:creator>
                   <text:p>Which one?</text:p></office:annotation> unclear.</text:p></text:note-body></text:note> end.</text:p>""",
            ),
        )

        val blocks = extractOdt(bytes)

        assertEquals("Claim1 end.", (blocks[0] as OfficeBlock.Paragraph).text)
        val notes = blocks.filterIsInstance<OfficeBlock.Note>()
        assertEquals(listOf("1", "C1"), notes.map { it.label })
        assertEquals("SourceC1 unclear.", notes[0].text)
        assertEquals("Which one?", notes[1].text)
    }

    @Test
    fun `odt deleted text in tracked changes is still not read as a comment or paragraph`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """<text:tracked-changes><text:changed-region text:id="ct1"><text:deletion>
                   <office:change-info><dc:creator>Alice</dc:creator><dc:date>2024-05-01T10:00:00</dc:date></office:change-info>
                   <text:p>Removed.</text:p></text:deletion></text:changed-region></text:tracked-changes>
                   <text:p>Kept.</text:p>""",
            ),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Kept.")), extractOdt(bytes))
    }

    @Test
    fun `byline shows author and minute, and drops what is not a date`() {
        assertEquals("Alice \u00B7 2024-05-01 10:15", commentByline("Alice", "2024-05-01T10:15:42Z"))
        assertEquals("Alice", commentByline("Alice", null))
        assertEquals("2024-05-01 10:15", commentByline("", "2024-05-01T10:15:00"))
        assertEquals("Alice", commentByline("  Alice  ", "yesterday"))
        assertNull(commentByline(null, null))
        assertNull(commentByline("  ", "garbage"))
    }
}
