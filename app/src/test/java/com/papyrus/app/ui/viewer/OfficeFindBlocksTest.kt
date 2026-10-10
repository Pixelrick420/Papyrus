package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.CharFormat
import com.papyrus.app.viewer.LineText
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import com.papyrus.app.viewer.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Find-in-file over the block kinds that carry formatting, lists and notes. */
class OfficeFindBlocksTest {

    private val bold = CharFormat(bold = true)

    @Test
    fun `a list item is searched by its text and never by its marker`() {
        val item = OfficeBlock.ListItem("first needle", marker = "1.", level = 0)

        assertEquals(listOf("first needle"), officeBlockLines(item))
        assertEquals(1, countOccurrences(item, "needle"))
        // The marker is drawn beside the text, so a match on it could be counted but never highlighted.
        assertEquals(0, countOccurrences(item, "1."))
    }

    @Test
    fun `a note is searched but its label is not`() {
        val note = OfficeBlock.Note("1", "see the needle")

        assertEquals(1, countOccurrences(note, "needle"))
        assertEquals(0, countOccurrences(note, "1"))
    }

    @Test
    fun `a divider never matches`() {
        assertEquals(emptyList<String>(), officeBlockLines(OfficeBlock.Divider))
        assertEquals(emptyList<Int>(), findBlockHits(listOf(OfficeBlock.Divider), "x"))
    }

    @Test
    fun `formatting does not change what is found or where`() {
        // Spans overlay the text; counting reads the text alone, so bold around a match changes nothing.
        val plain = OfficeBlock.Paragraph("a needle here")
        val styled = OfficeBlock.Paragraph("a needle here", spans = listOf(TextSpan(2, 8, bold)))

        assertEquals(findBlockHits(listOf(plain), "needle"), findBlockHits(listOf(styled), "needle"))
        assertEquals(findMatchRanges(plain.text, "needle"), findMatchRanges(styled.text, "needle"))
    }

    @Test
    fun `a cell's rich lines match the plain lines and carry spans re-based to each line`() {
        val cell = OfficeCell("ab\n\ncd", spans = listOf(TextSpan(1, 6, bold)))

        assertEquals(officeCellLines(cell), officeCellRichLines(cell).map { it.text })
        assertEquals(
            listOf(LineText("ab", listOf(TextSpan(1, 2, bold))), LineText("cd", listOf(TextSpan(0, 2, bold)))),
            officeCellRichLines(cell),
        )
    }
}
