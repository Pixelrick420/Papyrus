package com.papyrus.app.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.viewer.CharFormat
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeFixtures
import com.papyrus.app.viewer.OfficeTextExtractor
import com.papyrus.app.viewer.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a tap on office text meets: `formattedText` must attach its own listener that hands the URL
 *  back to the viewer, not the framework's UriHandler, and leave ranges without a target alone. */
class OfficeLinkSpansTest {

    private val palette = FormatPalette(surface = Color.White, link = Color.Blue)

    private val linkSpan = TextSpan(4, 8, CharFormat(link = "https://example.com/a"))

    private fun text(spans: List<TextSpan> = listOf(linkSpan), onLink: (String) -> Unit = {}) =
        formattedText("see site", spans, highlight = null, palette = palette, onLink = onLink)

    private fun links(annotated: androidx.compose.ui.text.AnnotatedString): List<LinkAnnotation.Url> =
        annotated.getLinkAnnotations(0, annotated.length).map { it.item as LinkAnnotation.Url }

    @Test
    fun `a span with a link becomes an annotation over exactly its range`() {
        val annotated = text()

        val range = annotated.getLinkAnnotations(0, annotated.length).single()

        assertEquals(4, range.start)
        assertEquals(8, range.end)
        assertEquals("https://example.com/a", links(annotated).single().url)
        assertNotNull(links(annotated).single().linkInteractionListener)
    }

    @Test
    fun `taking the annotation hands the url to the opener rather than the framework`() {
        var opened: String? = null

        val annotated = text(onLink = { opened = it })
        val annotation = links(annotated).single()
        annotation.linkInteractionListener!!.onClick(annotation)

        assertEquals("https://example.com/a", opened)
    }

    @Test
    fun `a span with no target adds no annotation and the default opener is harmless`() {
        val annotated = text(spans = listOf(TextSpan(0, 3, CharFormat(bold = true))))

        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        assertEquals(1, annotated.spanStyles.size)
        // The default onLink must not blow up when a listener is invoked anyway.
        links(annotated).forEach { it.linkInteractionListener!!.onClick(it) }
    }

    @Test
    fun `an unsafe scheme never reaches a span, so there is nothing to annotate`() {
        val rels = OfficeFixtures.docxLinkRels("rId5" to "https://example.com/a", "rId6" to "javascript:alert(1)")
        val body = """
            <w:p><w:r><w:t xml:space="preserve">see </w:t></w:r>
              <w:hyperlink r:id="rId5"><w:r><w:t>site</w:t></w:r></w:hyperlink>
              <w:r><w:t xml:space="preserve"> and </w:t></w:r>
              <w:hyperlink r:id="rId6"><w:r><w:t>bad</w:t></w:r></w:hyperlink></w:p>
        """.trimIndent()
        val bytes = OfficeFixtures.docx(OfficeFixtures.docxBody(body), relsXml = rels)

        val paragraph = OfficeTextExtractor(mediaDir = null, newParserFactory = OfficeFixtures::parser)
            .extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)[0] as OfficeBlock.Paragraph
        val annotated = formattedText(paragraph.text, paragraph.spans, highlight = null, palette = palette)

        assertEquals(listOf("https://example.com/a"), links(annotated).map { it.url })
    }
}
