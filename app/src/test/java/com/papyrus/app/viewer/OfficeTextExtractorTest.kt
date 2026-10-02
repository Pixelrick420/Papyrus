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
}
