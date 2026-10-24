package com.papyrus.app.data

import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shape of the indexed text for Office/EPUB documents: one block per line. */
class OfficeBlocksTextTest {

    private fun paragraph(text: String) = OfficeBlock.Paragraph(text)

    private val image = File("/tmp/opencode/picture.png")

    @Test
    fun `one block becomes one line`() {
        val text = officeBlocksToText(
            listOf(OfficeBlock.Heading("Scale", level = 1), paragraph("The paper trail.")),
            maxChars = 1_000,
        )

        assertEquals("Scale\nThe paper trail.", text)
    }

    @Test
    fun `a blank block starts no new line`() {
        val text = officeBlocksToText(
            listOf(paragraph("first"), paragraph("   "), paragraph("second")),
            maxChars = 1_000,
        )

        assertEquals("first\nsecond", text)
    }

    @Test
    fun `a divider contributes nothing, not even a line`() {
        val text = officeBlocksToText(
            listOf(paragraph("above"), OfficeBlock.Divider, paragraph("below")),
            maxChars = 1_000,
        )

        assertEquals("above\nbelow", text)
    }

    @Test
    fun `a table is one line, cells and rows joined by spaces`() {
        val table = OfficeBlock.Table(
            rows = listOf(
                listOf(OfficeCell("Scale", 0), OfficeCell("Paper", 1)),
                listOf(OfficeCell("A4", 0), OfficeCell("two", 1)),
            ),
        )

        assertEquals("Scale Paper A4 two", officeBlocksToText(listOf(table), maxChars = 1_000))
    }

    @Test
    fun `a list item contributes its text without its marker`() {
        // The marker is drawn beside the words, never inside them, so find offsets stay on the text.
        val item = OfficeBlock.ListItem(text = "check the index", marker = "2.", level = 0)

        assertEquals("check the index", officeBlocksToText(listOf(item), maxChars = 1_000))
    }

    @Test
    fun `an image contributes its alt text`() {
        val picture = OfficeBlock.Image(file = image, mimeType = "image/png", altText = "a paper map")

        assertEquals("a paper map", officeBlocksToText(listOf(picture), maxChars = 1_000))
    }

    @Test
    fun `an image without alt text contributes nothing`() {
        val picture = OfficeBlock.Image(file = image, mimeType = "image/png", altText = null)

        assertEquals("", officeBlocksToText(listOf(picture), maxChars = 1_000))
    }

    @Test
    fun `a note contributes its label and its text`() {
        val note = OfficeBlock.Note(label = "[1]", text = "see the appendix")

        assertEquals("[1] see the appendix", officeBlocksToText(listOf(note), maxChars = 1_000))
    }

    @Test
    fun `a note without a label contributes only its text`() {
        val note = OfficeBlock.Note(label = "", text = "see the appendix")

        assertEquals("see the appendix", officeBlocksToText(listOf(note), maxChars = 1_000))
    }

    @Test
    fun `text is cut at the cap without splitting the last block into a new line`() {
        val text = officeBlocksToText(listOf(paragraph("abc"), paragraph("def")), maxChars = 5)

        assertEquals("abc\nd", text)
        assertEquals(5, text.length)
    }

    @Test
    fun `a block that exactly fills the cap ends the extraction`() {
        val text = officeBlocksToText(listOf(paragraph("abcde"), paragraph("fghij")), maxChars = 5)

        assertEquals("abcde", text)
    }

    @Test
    fun `nothing is indexed when the cap leaves no room`() {
        assertEquals("", officeBlocksToText(listOf(paragraph("abc")), maxChars = 0))
        assertEquals("", officeBlocksToText(listOf(paragraph("abc")), maxChars = -1))
    }

    @Test
    fun `a long document never exceeds the cap`() {
        val blocks = (1..100).map { paragraph("paragraph number $it") }

        val text = officeBlocksToText(blocks, maxChars = 120)

        assertTrue("length ${text.length}", text.length <= 120)
        assertTrue(text.startsWith("paragraph number 1\nparagraph number 2"))
    }
}
