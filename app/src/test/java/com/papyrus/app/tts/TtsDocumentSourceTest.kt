package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.viewer.OfficeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure: builds the read-aloud queue and the resume mapping from plain values. */
class TtsDocumentSourceTest {

    // --- Paragraph splitting -------------------------------------------------------------------

    @Test
    fun `blank lines split paragraphs and line breaks collapse`() {
        val text = "First line\nsecond line.\n\n\nThird paragraph\nover two lines."
        val paragraphs = TtsDocumentSource.paragraphs(text)
        assertEquals(2, paragraphs.size)
        assertEquals("First line second line.", paragraphs[0].text)
        assertEquals("Third paragraph over two lines.", paragraphs[1].text)
    }

    @Test
    fun `carriage returns end lines like newlines`() {
        val crlf = TtsDocumentSource.paragraphs("one\r\ntwo\r\n\r\nthree")
        assertEquals(listOf("one two", "three"), crlf.map { it.text })

        val cr = TtsDocumentSource.paragraphs("one\rtwo\r\rthree")
        assertEquals(listOf("one two", "three"), cr.map { it.text })
    }

    @Test
    fun `a paragraph carries the offset it started at`() {
        val text = "alpha\n\nbeta\n\ngamma"
        val paragraphs = TtsDocumentSource.paragraphs(text)
        assertEquals(text.indexOf("alpha"), paragraphs[0].start)
        assertEquals(text.indexOf("beta"), paragraphs[1].start)
        assertEquals(text.indexOf("gamma"), paragraphs[2].start)
        assertEquals("alpha", text.substring(paragraphs[0].start, paragraphs[0].end))
        assertEquals("beta", text.substring(paragraphs[1].start, paragraphs[1].end))
    }

    @Test
    fun `blank-only and empty text produce no paragraphs`() {
        assertTrue(TtsDocumentSource.paragraphs("").isEmpty())
        assertTrue(TtsDocumentSource.paragraphs("\n\n\r\n   \n").isEmpty())
    }

    @Test
    fun `trailing whitespace is trimmed off a paragraph`() {
        val paragraphs = TtsDocumentSource.paragraphs("  padded text   \n\nnext  ")
        assertEquals("padded text", paragraphs[0].text)
        assertEquals("next", paragraphs[1].text)
    }

    // --- Per-format units ----------------------------------------------------------------------

    @Test
    fun `plain text anchors each unit to its chunk index`() {
        val chunks = listOf("first chunk line\n\nfirst chunk second", "second chunk")
        val units = TtsDocumentSource.unitsFromPlainText(chunks)
        assertEquals(listOf(0, 0, 1), units.map { it.anchor })
        assertEquals("first chunk line", units[0].text)
        assertEquals("second chunk", units[2].text)
    }

    @Test
    fun `markdown anchors each unit to its character offset`() {
        val text = "intro\n\nbody text"
        val units = TtsDocumentSource.unitsFromMarkdown(text)
        assertEquals(2, units.size)
        assertEquals(0, units[0].anchor)
        assertEquals(text.indexOf("body"), units[1].anchor)
    }

    @Test
    fun `pdf pages anchor each unit to its page index`() {
        val pages = listOf("page zero\n\nsecond para", "", "page two")
        val units = TtsDocumentSource.unitsFromPdfPages(pages)
        assertEquals(listOf(0, 0, 2), units.map { it.anchor })
    }

    @Test
    fun `office blocks read in order with the block index as the anchor`() {
        val blocks = listOf(
            OfficeBlock.Heading("Title", level = 1),
            OfficeBlock.Paragraph(""),
            OfficeBlock.ListItem("Point", marker = "•", level = 0),
            OfficeBlock.Divider,
            OfficeBlock.Image(File("pic.png"), mimeType = "image/png"),
            OfficeBlock.Paragraph("Last"),
        )
        val units = TtsDocumentSource.unitsFromOffice(blocks)
        assertEquals(listOf(0, 2, 5), units.map { it.anchor })
        assertEquals(listOf("Title", "Point", "Last"), units.map { it.text })
    }

    @Test
    fun `office tables read row-wise and notes gain their byline`() {
        val blocks = listOf(
            OfficeBlock.Table(
                rows = listOf(
                    listOf(cell("a"), cell("b")),
                    listOf(cell("c"), cell("d")),
                ),
            ),
            OfficeBlock.Note(label = "1", text = "A remark", byline = "Ada"),
        )
        val units = TtsDocumentSource.unitsFromOffice(blocks)
        assertEquals(listOf("a b c d", "A remark Ada"), units.map { it.text })
    }

    @Test
    fun `the generic entry point dispatches on the format`() {
        assertEquals(
            listOf("hi"),
            TtsDocumentSource.units(DocumentFormat.TEXT, plainTextChunks = listOf("hi")).map { it.text },
        )
        assertTrue(TtsDocumentSource.units(DocumentFormat.UNKNOWN, plainTextChunks = listOf("hi")).isEmpty())
    }

    // --- Resume mapping ------------------------------------------------------------------------

    @Test
    fun `a page or chunk location lands on the first unit at or after it`() {
        val units = listOf(TtsUnit("a", 0), TtsUnit("b", 0), TtsUnit("c", 2), TtsUnit("d", 5))
        val chunk = LocationKind.CHUNK
        assertEquals(0, TtsDocumentSource.startIndexFor(units, ReadingLocation(chunk, 0)))
        assertEquals(2, TtsDocumentSource.startIndexFor(units, ReadingLocation(chunk, 2)))
        // Between known anchors, the next one wins.
        assertEquals(3, TtsDocumentSource.startIndexFor(units, ReadingLocation(chunk, 3)))
    }

    @Test
    fun `a location past the end clamps to the last unit`() {
        val units = listOf(TtsUnit("a", 0), TtsUnit("b", 1))
        assertEquals(1, TtsDocumentSource.startIndexFor(units, ReadingLocation(LocationKind.PAGE, 99)))
    }

    @Test
    fun `a markdown offset lands on the paragraph containing it`() {
        val text = "alpha paragraph\n\nbeta paragraph\n\ngamma paragraph"
        val units = TtsDocumentSource.unitsFromMarkdown(text)
        val mid = text.indexOf("paragraph", startIndex = text.indexOf("beta"))
        assertEquals(1, TtsDocumentSource.startIndexFor(units, ReadingLocation(LocationKind.CHAR, mid)))
        // Before the first paragraph start is the first paragraph.
        assertEquals(0, TtsDocumentSource.startIndexFor(units, ReadingLocation(LocationKind.CHAR, 0)))
    }

    @Test
    fun `an empty document starts at zero`() {
        assertEquals(0, TtsDocumentSource.startIndexFor(emptyList(), ReadingLocation(LocationKind.PAGE, 4)))
    }

    private fun cell(text: String) = com.papyrus.app.viewer.OfficeCell(text)
}
