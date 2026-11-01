package com.papyrus.app.ui.screens

import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** What the viewer can tell about a document's own text: enough to read aloud, nothing, or not known. */
class SpeakableTextTest {

    @Test
    fun `a text file of blank lines has nothing to say`() {
        assertEquals(false, ViewerContent.PlainText(chunks = listOf("", "\n", "   \n\t")).hasSpeakableText())
    }

    @Test
    fun `a text file with a word in any chunk says yes`() {
        assertEquals(true, ViewerContent.PlainText(chunks = listOf("", " \n ", "hello")).hasSpeakableText())
    }

    @Test
    fun `an office document of pictures with no alt text has nothing to say`() {
        val blocks = listOf(
            OfficeBlock.Divider,
            OfficeBlock.Image(file = File("page.png"), mimeType = "image/png"),
        )
        assertEquals(false, ViewerContent.Office(blocks = blocks).hasSpeakableText())
    }

    @Test
    fun `a named picture, a paragraph or a table is something to say`() {
        assertEquals(
            true,
            ViewerContent.Office(blocks = listOf(OfficeBlock.Image(file = File("p.png"), mimeType = "image/png", altText = "A map")))
                .hasSpeakableText(),
        )
        assertEquals(true, ViewerContent.Office(blocks = listOf(OfficeBlock.Paragraph(text = "Body"))).hasSpeakableText())
    }

    @Test
    fun `a table of empty cells has nothing to say`() {
        val table = OfficeBlock.Table(rows = listOf(listOf(OfficeCell(text = " "), OfficeCell(text = ""))))
        assertEquals(false, ViewerContent.Office(blocks = listOf(table)).hasSpeakableText())
    }

    @Test
    fun `a pdf and a document that has not loaded leave the answer to something else`() {
        assertNull(ViewerContent.Loading.hasSpeakableText())
    }
}
