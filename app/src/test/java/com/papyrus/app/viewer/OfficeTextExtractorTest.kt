package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Table geometry and merge handling, where cell position depends on every earlier cell. */
class OfficeTextExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun extractor(mediaDir: File? = null) =
        OfficeTextExtractor(mediaDir = mediaDir, newParserFactory = OfficeFixtures::parser)

    private fun extractDocx(bytes: ByteArray, mediaDir: File? = null) =
        extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

    private fun extractOdt(bytes: ByteArray, mediaDir: File? = null) =
        extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.ODT)

    private fun OfficeBlock.Table.rowCells(row: Int) = rows[row]

    @Test
    fun `docx paragraphs and headings come out in document order`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Report</w:t></w:r></w:p>
                <w:p><w:r><w:t>First body.</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>Section</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second body.</w:t></w:r></w:p>
                """.trimIndent(),
            ),
        )

        val blocks = extractDocx(bytes)

        assertEquals(
            listOf(
                OfficeBlock.Heading("Report", 1),
                OfficeBlock.Paragraph("First body."),
                OfficeBlock.Heading("Section", 2),
                OfficeBlock.Paragraph("Second body."),
            ),
            blocks,
        )
    }

    @Test
    fun `whitespace between structural tags is not content`() {
        // Word pretty-prints its XML; that indentation is not paragraph text.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p>
                    <w:r>
                        <w:t>Body</w:t>
                    </w:r>
                </w:p>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(OfficeBlock.Paragraph("Body")), extractDocx(bytes))
    }

    @Test
    fun `tab and line break inside a paragraph survive`() {
        // Regression: w:tab and w:br carry no text, so collecting only inside w:t drops them.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody("<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t><w:br/><w:t>c</w:t></w:r></w:p>"),
        )

        assertEquals(listOf(OfficeBlock.Paragraph("a\tb\nc")), extractDocx(bytes))
    }

    @Test
    fun `odt heading level and repeated spaces are read`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <text:h text:outline-level="3">Deep</text:h>
                <text:p>a<text:s text:c="3"/>b</text:p>
                <text:p>x<text:tab/>y<text:line-break/>z</text:p>
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(
                OfficeBlock.Heading("Deep", 3),
                OfficeBlock.Paragraph("a   b"),
                OfficeBlock.Paragraph("x\ty\nz"),
            ),
            extractOdt(bytes),
        )
    }

    @Test
    fun `docx table cells carry their grid column`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>c</w:t></w:r></w:p></w:tc></w:tr>
                  <w:tr><w:tc><w:p><w:r><w:t>d</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>e</w:t></w:r></w:p></w:tc>
                      <w:tc><w:p><w:r><w:t>f</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table

        assertEquals(2, table.rows.size)
        assertEquals(listOf(0, 1, 2), table.rowCells(0).map { it.column })
        assertEquals(listOf("a", "b", "c"), table.rowCells(0).map { it.text })
        assertEquals(listOf("d", "e", "f"), table.rowCells(1).map { it.text })
    }

    @Test
    fun `gridSpan widens the cell and shifts the ones after it`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>wide</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>right</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table

        val cells = table.rowCells(0)
        assertEquals(listOf(0, 2), cells.map { it.column })
        assertEquals(listOf(2, 1), cells.map { it.colspan })
        assertEquals(listOf(1, 2), cells.map { it.lastColumn })
    }

    @Test
    fun `vMerge restart makes the origin cell span the covered rows`() {
        // Treating restart as a continuation records no origin, so rowspan degrades to 1.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>tall</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p><w:r><w:t></w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>y</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table
        val origin = table.rowCells(0).first()

        assertEquals("tall", origin.text)
        assertEquals(2, origin.rowspan)
        // Repeated in row 2, the blank cell would shift y and draw a border through the merge.
        assertEquals(listOf("y"), table.rowCells(1).map { it.text })
        assertEquals(1, table.rowCells(1).single().column)
    }

    @Test
    fun `a vMerge continuation with no origin is an ordinary cell`() {
        // A truncated document can start mid-merge; the cell must still render.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p><w:r><w:t>orphan</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>next</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table

        assertEquals(listOf("orphan", "next"), table.rowCells(0).map { it.text })
        assertEquals(1, table.rowCells(0).first().rowspan)
    }

    @Test
    fun `a merge that stops reaching later rows does not inflate a fresh cell`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p><w:r><w:t></w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>2</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>fresh</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>3</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table

        assertEquals(2, table.rowCells(0).first().rowspan)
        assertEquals("fresh", table.rowCells(2).first().text)
        assertEquals(1, table.rowCells(2).first().rowspan)
    }

    @Test
    fun `odt covered cell widens the cell to its left`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <table:table>
                  <table:table-row>
                    <table:table-cell><text:p>wide</text:p></table:table-cell>
                    <table:covered-table-cell/>
                    <table:table-cell><text:p>right</text:p></table:table-cell>
                  </table:table-row>
                </table:table>
                """.trimIndent(),
            ),
        )

        val table = extractOdt(bytes).single() as OfficeBlock.Table
        val cells = table.rowCells(0)

        assertEquals(listOf("wide", "right"), cells.map { it.text })
        assertEquals(listOf(0, 2), cells.map { it.column })
        assertEquals(2, cells.first().colspan)
    }

    @Test
    fun `a cell with several paragraphs keeps them separated`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr><w:tc>
                    <w:p><w:r><w:t>line one</w:t></w:r></w:p>
                    <w:p><w:r><w:t>line two</w:t></w:r></w:p>
                  </w:tc></w:tr>
                </w:tbl>
                """.trimIndent(),
            ),
        )

        val table = extractDocx(bytes).single() as OfficeBlock.Table

        assertEquals("line one\nline two", table.rowCells(0).single().text)
    }

    @Test
    fun `a nested table is flattened into its enclosing cell without duplicating the outer rows`() {
        // Regression: a nested row called Grid.startRow() and the outer table vanished.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>outer</w:t></w:r></w:p></w:tc>
                    <w:tc>
                      <w:tbl>
                        <w:tr><w:tc><w:p><w:r><w:t>inner</w:t></w:r></w:p></w:tc></w:tr>
                      </w:tbl>
                    </w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:t>after</w:t></w:r></w:p>
                """.trimIndent(),
            ),
        )

        val blocks = extractDocx(bytes)
        val table = blocks.filterIsInstance<OfficeBlock.Table>().single()

        assertEquals(1, table.rows.size)
        assertEquals(2, table.rowCells(0).size)
        assertEquals("outer", table.rowCells(0).first().text)
        // Nested text folds into the enclosing cell rather than becoming its own block.
        assertTrue(
            "nested text should survive inside the enclosing cell, was: ${table.rowCells(0)[1].text}",
            table.rowCells(0)[1].text.contains("inner"),
        )
        assertEquals(1, blocks.count { it is OfficeBlock.Table })
        assertEquals(OfficeBlock.Paragraph("after"), blocks.last())
    }

    @Test
    fun `a table before a paragraph does not swallow the paragraph`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <w:p><w:r><w:t>after</w:t></w:r></w:p>
                """.trimIndent(),
            ),
        )

        val blocks = extractDocx(bytes)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is OfficeBlock.Table)
        assertEquals(OfficeBlock.Paragraph("after"), blocks[1])
    }

    @Test
    fun `an image is written to the media dir and inserted where it appeared`() {
        val mediaDir = temp.newFolder("media")
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody(
                """
                <w:p><w:r><w:t>before</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>
                <w:p><w:r><w:t>after</w:t></w:r></w:p>
                """.trimIndent(),
            ),
            relsXml = OfficeFixtures.docxRels("rId7" to "media/pic.png"),
            media = mapOf("pic.png" to OfficeFixtures.PNG_1X1),
        )

        val blocks = extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

        assertEquals(3, blocks.size)
        val image = blocks[1] as OfficeBlock.Image
        assertEquals("image/png", image.mimeType)
        assertEquals(OfficeFixtures.PNG_1X1.toList(), image.file.readBytes().toList())
        assertEquals(OfficeBlock.Paragraph("before"), blocks[0])
        assertEquals(OfficeBlock.Paragraph("after"), blocks[2])
    }

    @Test
    fun `a relationship with no media entry is skipped and the text still renders`() {
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody(
                """<w:p><w:r><w:t>keep me</w:t></w:r></w:p>
                   <w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""".trimIndent(),
            ),
            relsXml = OfficeFixtures.docxRels("rId7" to "media/missing.png"),
        )

        val blocks = extractDocx(bytes, temp.newFolder("media"))

        assertEquals(listOf(OfficeBlock.Paragraph("keep me")), blocks)
    }

    @Test
    fun `with no media dir the extractor still returns the text`() {
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody(
                """<w:p><w:r><w:t>text only</w:t></w:r></w:p><w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""",
            ),
            relsXml = OfficeFixtures.docxRels("rId7" to "media/pic.png"),
            media = mapOf("pic.png" to OfficeFixtures.PNG_1X1),
        )

        assertEquals(listOf(OfficeBlock.Paragraph("text only")), extractDocx(bytes, mediaDir = null))
    }

    @Test
    fun `a zip entry name escaping the media dir cannot write outside it`() {
        val mediaDir = temp.newFolder("media")
        // The archive holds "word/../../escaped.png", so only the write is under test.
        val traversal = "word/../../escaped.png"
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody(
                """<w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""",
            ),
            relsXml = OfficeFixtures.docxRels("rId7" to "../../escaped.png"),
            media = mapOf(traversal.removePrefix("word/") to OfficeFixtures.PNG_1X1),
            rawEntries = mapOf(traversal to OfficeFixtures.PNG_1X1),
        )

        extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

        // Sanitised to a bare name, so it lands beside the images rather than above them.
        assertEquals(listOf("escaped.png"), mediaDir.listFiles()!!.map { it.name })
        assertEquals(listOf("media"), temp.root.listFiles()!!.map { it.name })
    }

    @Test
    fun `an image larger than the per-file cap is rejected without writing`() {
        val mediaDir = temp.newFolder("media")
        val oversized = ByteArray(OfficeTextExtractor.MAX_IMAGE_BYTES + 1) { 0x41 }
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody(
                """<w:p><w:r><w:t>text</w:t></w:r></w:p><w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>""",
            ),
            relsXml = OfficeFixtures.docxRels("rId7" to "media/big.png"),
            media = mapOf("big.png" to oversized),
        )

        val blocks = extractDocx(bytes, mediaDir)

        assertEquals(listOf(OfficeBlock.Paragraph("text")), blocks)
        assertEquals(0, mediaDir.listFiles()!!.size)
    }

    @Test
    fun `a zip without the body part is rejected with a message naming it`() {
        val bytes = OfficeFixtures.zipOf(mapOf("[Content_Types].xml" to "<Types/>".toByteArray()))

        val error = runCatching { extractDocx(bytes) }.exceptionOrNull()

        assertTrue("expected an IOException, got $error", error is java.io.IOException)
        assertTrue(error!!.message!!.contains("word/document.xml"))
    }

    @Test(expected = java.io.IOException::class)
    fun `a non-office format is rejected rather than silently producing nothing`() {
        // An empty list here reads as "opened successfully, nothing in it".
        extractor().extract({ java.io.ByteArrayInputStream(ByteArray(0)) }, DocumentFormat.PDF)
    }

    @Test
    fun `a table row cap drops the table instead of emitting it truncated`() {
        val rows = (0 until OfficeTextExtractor.MAX_TABLE_ROWS + 5).joinToString("") { index ->
            "<w:tr><w:tc><w:p><w:r><w:t>r$index</w:t></w:r></w:p></w:tc></w:tr>"
        }
        val bytes = OfficeFixtures.docx(OfficeFixtures.docxBody("<w:tbl>$rows</w:tbl>"))

        val blocks = extractDocx(bytes)

        // A half-rendered table reads as data loss, so the whole table is dropped.
        assertTrue(blocks.none { it is OfficeBlock.Table })
    }

    @Test
    fun `an empty archive body yields no blocks rather than throwing`() {
        val bytes = OfficeFixtures.docx(OfficeFixtures.docxBody(""))

        assertTrue(extractDocx(bytes).isEmpty())
    }

    @Test
    fun `sanitiseName keeps a usable file name from a hostile entry name`() {
        // A surviving separator or parent reference is a path the media writer would follow.
        assertEquals("escaped.png", OfficeTextExtractor.sanitiseName("../../etc/escaped.png"))
        assertEquals(".._.._etc_passwd", OfficeTextExtractor.sanitiseName("..\\..\\etc\\passwd"))
        // Nothing usable left after the basename is taken, so a fixed name stands in.
        assertEquals("image", OfficeTextExtractor.sanitiseName("///"))
        // Whitespace is not blank once substituted, and "_" is a valid, harmless file name.
        assertEquals("___", OfficeTextExtractor.sanitiseName("   "))
        assertNotNull(OfficeTextExtractor.guessMimeType("photo.JPEG"))
        assertEquals("image/jpeg", OfficeTextExtractor.guessMimeType("photo.JPEG"))
        assertEquals("image/*", OfficeTextExtractor.guessMimeType("archive.bin"))
    }

    // ---- Real-file regressions: each case below was reproduced on a LibreOffice-written document. ----

    @Test
    fun `docx tab stop definitions are not tab characters`() {
        // <w:tabs><w:tab/></w:tabs> declares a stop; it used to add one "\t" per stop before the text.
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p>
                  <w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/><w:tab w:val="right" w:pos="9000"/></w:tabs></w:pPr>
                  <w:r><w:t>Name</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>Role</w:t></w:r>
                </w:p>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Name\tRole")), extractDocx(bytes))
    }

    @Test
    fun `docx non-breaking hyphen is kept`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody("<w:p><w:r><w:t>e</w:t><w:noBreakHyphen/><w:t>mail</w:t></w:r></w:p>"),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("e-mail")), extractDocx(bytes))
    }

    @Test
    fun `docx images stay between the paragraphs they were between`() {
        // Insert positions were recorded before any image went in, so images drifted upward and clustered.
        fun imageParagraph() = """<w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>"""
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p><w:r><w:t>one</w:t></w:r></w:p>${imageParagraph()}
                <w:p><w:r><w:t>two</w:t></w:r></w:p>${imageParagraph()}
                <w:p><w:r><w:t>three</w:t></w:r></w:p>${imageParagraph()}
                """.trimIndent(),
            ),
            relsXml = OfficeFixtures.docxRels("rId1" to "media/a.png"),
            media = mapOf("a.png" to OfficeFixtures.PNG_1X1),
        )

        val kinds = extractDocx(bytes, temp.newFolder("media")).map {
            when (it) {
                is OfficeBlock.Paragraph -> it.text
                is OfficeBlock.Image -> "<img>"
                else -> "?"
            }
        }

        assertEquals(listOf("one", "<img>", "two", "<img>", "three", "<img>"), kinds)
    }

    @Test
    fun `docx text box is read once from mc Choice and the fallback copy is ignored`() {
        fun box() = """<w:r><w:txbxContent><w:p><w:r><w:t>In the box</w:t></w:r></w:p></w:txbxContent></w:r>"""
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p>
                  <w:r><w:t>Anchor</w:t></w:r>
                  <mc:AlternateContent>
                    <mc:Choice Requires="wps">${box()}</mc:Choice>
                    <mc:Fallback>${box()}<w:r><v:imagedata r:id="rId1"/></w:r></mc:Fallback>
                  </mc:AlternateContent>
                </w:p>
                """.trimIndent(),
            ),
            relsXml = OfficeFixtures.docxRels("rId1" to "media/a.png"),
            media = mapOf("a.png" to OfficeFixtures.PNG_1X1),
        )

        val blocks = extractDocx(bytes, temp.newFolder("media"))

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Paragraph("In the box"), OfficeBlock.Paragraph("Anchor")),
            blocks,
        )
    }

    @Test
    fun `docx text box paragraphs are separate blocks and the anchor keeps its own text`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody(
                """
                <w:p>
                  <w:r><w:t>Before</w:t></w:r>
                  <w:r><w:txbxContent>
                    <w:p><w:r><w:t>Box A</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Box B</w:t></w:r></w:p>
                  </w:txbxContent></w:r>
                  <w:r><w:t xml:space="preserve"> after</w:t></w:r>
                </w:p>
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.Paragraph("Box A"),
                OfficeBlock.Paragraph("Box B"),
                OfficeBlock.Paragraph("Before after"),
            ),
            extractDocx(bytes),
        )
    }

    @Test
    fun `odt text after a footnote survives and the footnote is gathered after the body`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <text:p>Revenue grew<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation>
                <text:note-body><text:p>Unaudited figure.</text:p></text:note-body></text:note> by ten percent.</text:p>
                """.trimIndent(),
            ),
        )

        val blocks = extractOdt(bytes)

        // The marker is part of the line, raised; the note's own text is not, and follows the body.
        assertEquals(3, blocks.size)
        val paragraph = blocks[0] as OfficeBlock.Paragraph
        assertEquals("Revenue grew1 by ten percent.", paragraph.text)
        assertEquals(listOf(TextSpan(12, 13, CharFormat(script = ScriptShift.SUPER))), paragraph.spans)
        assertEquals(OfficeBlock.Divider, blocks[1])
        assertEquals(OfficeBlock.Note("1", "Unaudited figure."), blocks[2])
    }

    @Test
    fun `odt comment text author and date stay out of the paragraph`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <text:p>Check this <office:annotation><dc:creator>Alice</dc:creator>
                <dc:date>2024-05-01T10:00:00</dc:date><text:p>Is it right?</text:p></office:annotation>number twice.</text:p>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Check this number twice.")), extractOdt(bytes))
    }

    @Test
    fun `odt deleted tracked change text is not a paragraph`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <text:tracked-changes><text:changed-region text:id="ct1"><text:deletion>
                  <text:p>This sentence was deleted.</text:p></text:deletion></text:changed-region></text:tracked-changes>
                <text:p>Kept.</text:p>
                """.trimIndent(),
            ),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Kept.")), extractOdt(bytes))
    }

    @Test
    fun `odt text around a text box is kept and the box is its own paragraph`() {
        // LibreOffice writes the frame first, so the paragraph's own text comes after the nested </text:p>.
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <text:p><draw:frame><draw:text-box><text:p>Inside the box.</text:p></draw:text-box></draw:frame>Before frame after frame.</text:p>
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Paragraph("Inside the box."), OfficeBlock.Paragraph("Before frame after frame.")),
            extractOdt(bytes),
        )
    }

    @Test
    fun `odt vertical merge in a middle column does not stretch the neighbouring cell`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <table:table>
                  <table:table-column table:number-columns-repeated="3"/>
                  <table:table-row>
                    <table:table-cell><text:p>A1</text:p></table:table-cell>
                    <table:table-cell table:number-rows-spanned="2"><text:p>tall</text:p></table:table-cell>
                    <table:table-cell><text:p>C1</text:p></table:table-cell>
                  </table:table-row>
                  <table:table-row>
                    <table:table-cell><text:p>A2</text:p></table:table-cell>
                    <table:covered-table-cell/>
                    <table:table-cell><text:p>C2</text:p></table:table-cell>
                  </table:table-row>
                </table:table>
                """.trimIndent(),
            ),
        )

        val table = extractOdt(bytes).single() as OfficeBlock.Table

        assertEquals(2, table.rowCells(0)[1].rowspan)
        assertEquals(listOf("A2", "C2"), table.rowCells(1).map { it.text })
        assertEquals(listOf(1, 1), table.rowCells(1).map { it.colspan })
        assertEquals(listOf(0, 2), table.rowCells(1).map { it.column })
    }

    @Test
    fun `odt column span from the attribute places later cells correctly`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <table:table>
                  <table:table-row>
                    <table:table-cell table:number-columns-spanned="2"><text:p>wide</text:p></table:table-cell>
                    <table:covered-table-cell/>
                    <table:table-cell><text:p>right</text:p></table:table-cell>
                  </table:table-row>
                </table:table>
                """.trimIndent(),
            ),
        )

        val cells = (extractOdt(bytes).single() as OfficeBlock.Table).rowCells(0)

        assertEquals(listOf(0, 2), cells.map { it.column })
        assertEquals(2, cells.first().colspan)
    }

    @Test
    fun `odt repeated cells expand but never past the declared columns`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                """
                <table:table>
                  <table:table-column table:number-columns-repeated="4"/>
                  <table:table-row>
                    <table:table-cell><text:p>a</text:p></table:table-cell>
                    <table:table-cell table:number-columns-repeated="2"/>
                    <table:table-cell><text:p>last</text:p></table:table-cell>
                  </table:table-row>
                  <table:table-row>
                    <table:table-cell><text:p>x</text:p></table:table-cell>
                    <table:table-cell table:number-columns-repeated="60"/>
                  </table:table-row>
                </table:table>
                """.trimIndent(),
            ),
        )

        val table = extractOdt(bytes).single() as OfficeBlock.Table

        assertEquals(listOf(0, 1, 2, 3), table.rowCells(0).map { it.column })
        assertEquals(listOf(0, 1, 2, 3), table.rowCells(1).map { it.column })
    }

    @Test
    fun `odt images stay between the paragraphs they were between`() {
        fun image() = """<text:p><draw:frame><draw:image xlink:href="Pictures/a.png"/></draw:frame></text:p>"""
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent(
                "<text:p>one</text:p>${image()}<text:p>two</text:p>${image()}<text:p>three</text:p>",
            ),
            media = mapOf("a.png" to OfficeFixtures.PNG_1X1),
        )

        val kinds = extractOdt(bytes, temp.newFolder("media")).map {
            when (it) {
                is OfficeBlock.Paragraph -> it.text
                is OfficeBlock.Image -> "<img>"
                else -> "?"
            }
        }

        assertEquals(listOf("one", "<img>", "two", "<img>", "three"), kinds)
    }
}
