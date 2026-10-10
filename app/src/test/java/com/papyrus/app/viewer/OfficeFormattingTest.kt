package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the extractor says about *how* text looks and is arranged (formatting, lists, links,
 * alignment, notes, shading, image metadata), unlike [OfficeTextExtractorTest]'s geometry checks.
 */
class OfficeFormattingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bold = CharFormat(bold = true)
    private val italic = CharFormat(italic = true)

    private fun extractor(mediaDir: File? = null) =
        OfficeTextExtractor(mediaDir = mediaDir, newParserFactory = OfficeFixtures::parser)

    private fun extractDocx(bytes: ByteArray, mediaDir: File? = null) =
        extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)

    private fun extractOdt(bytes: ByteArray, mediaDir: File? = null) =
        extractor(mediaDir).extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.ODT)

    private fun part(root: String, inner: String) =
        """<?xml version="1.0" encoding="UTF-8"?><w:$root xmlns:w="$W">$inner</w:$root>"""

    /** A DOCX whose optional parts are given as the inner XML of their root element. */
    private fun docx(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        footnotes: String? = null,
        endnotes: String? = null,
        rels: String? = null,
        media: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val raw = buildMap<String, ByteArray> {
            if (styles != null) put("word/styles.xml", part("styles", styles).toByteArray())
            if (numbering != null) put("word/numbering.xml", part("numbering", numbering).toByteArray())
            if (footnotes != null) put("word/footnotes.xml", part("footnotes", footnotes).toByteArray())
            if (endnotes != null) put("word/endnotes.xml", part("endnotes", endnotes).toByteArray())
        }
        return OfficeFixtures.docx(OfficeFixtures.docxBody(body), relsXml = rels, media = media, rawEntries = raw)
    }

    private fun item(text: String, level: Int = 0) =
        """<w:p><w:pPr><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""

    // ---- DOCX: character formatting ----

    @Test
    fun `docx bold and italic runs become spans over the unchanged text`() {
        val blocks = extractDocx(
            docx("""<w:p><w:r><w:t xml:space="preserve">plain </w:t></w:r><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>bold</w:t></w:r></w:p>"""),
        )

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.Paragraph("plain bold", spans = listOf(TextSpan(6, 10, CharFormat(bold = true, italic = true)))),
            ),
            blocks,
        )
    }

    @Test
    fun `docx adjacent runs with the same formatting coalesce into one span`() {
        val blocks = extractDocx(
            docx("""<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>ab</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>cd</w:t></w:r></w:p>"""),
        )

        assertEquals(listOf(TextSpan(0, 4, bold)), (blocks[0] as OfficeBlock.Paragraph).spans)
    }

    @Test
    fun `docx paragraph and character styles are inherited and direct formatting overrides them`() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="Callout"><w:name w:val="Callout"/><w:rPr><w:b/></w:rPr></w:style>
            <w:style w:type="character" w:styleId="Emphasis"><w:name w:val="Emphasis"/><w:rPr><w:i/></w:rPr></w:style>
        """.trimIndent()
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Callout"/></w:pPr>
              <w:r><w:t xml:space="preserve">one </w:t></w:r>
              <w:r><w:rPr><w:rStyle w:val="Emphasis"/></w:rPr><w:t xml:space="preserve">two </w:t></w:r>
              <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>three</w:t></w:r>
            </w:p>
        """.trimIndent()

        val paragraph = extractDocx(docx(body, styles = styles))[0] as OfficeBlock.Paragraph

        assertEquals("one two three", paragraph.text)
        assertEquals(
            listOf(TextSpan(0, 4, bold), TextSpan(4, 8, CharFormat(bold = true, italic = true))),
            paragraph.spans,
        )
    }

    @Test
    fun `docx underline strike script colour highlight and monospace font are all read`() {
        val body = """
            <w:p><w:r><w:rPr>
              <w:u w:val="single"/><w:strike/><w:vertAlign w:val="superscript"/>
              <w:color w:val="FF0000"/><w:highlight w:val="yellow"/><w:rFonts w:ascii="Courier New"/>
            </w:rPr><w:t>x</w:t></w:r></w:p>
        """.trimIndent()

        val paragraph = extractDocx(docx(body))[0] as OfficeBlock.Paragraph

        assertEquals(
            listOf(
                TextSpan(
                    0, 1,
                    CharFormat(
                        underline = true, strike = true, script = ScriptShift.SUPER, mono = true,
                        color = 0xFF0000, background = 0xFFFF00,
                    ),
                ),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun `docx automatic colour is left to the theme rather than pinned`() {
        val blocks = extractDocx(docx("""<w:p><w:r><w:rPr><w:color w:val="auto"/></w:rPr><w:t>x</w:t></w:r></w:p>"""))

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("x")), blocks)
    }

    @Test
    fun `docx paragraph mark properties do not format the paragraph's text`() {
        val blocks = extractDocx(
            docx("""<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:t>text</w:t></w:r></w:p>"""),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("text")), blocks)
    }

    @Test
    fun `docx tracked formatting change reads the current properties not the previous ones`() {
        val body = """
            <w:p><w:r><w:rPr><w:b/><w:rPrChange w:id="1"><w:rPr><w:i/></w:rPr></w:rPrChange></w:rPr><w:t>x</w:t></w:r></w:p>
        """.trimIndent()

        assertEquals(listOf(TextSpan(0, 1, bold)), (extractDocx(docx(body))[0] as OfficeBlock.Paragraph).spans)
    }

    // ---- DOCX: links ----

    @Test
    fun `docx hyperlink text carries its target and an unsafe scheme is dropped`() {
        val rels = OfficeFixtures.docxLinkRels("rId5" to "https://example.com/a", "rId6" to "javascript:alert(1)")
        val body = """
            <w:p><w:r><w:t xml:space="preserve">see </w:t></w:r>
              <w:hyperlink r:id="rId5"><w:r><w:t>site</w:t></w:r></w:hyperlink>
              <w:r><w:t xml:space="preserve"> and </w:t></w:r>
              <w:hyperlink r:id="rId6"><w:r><w:t>bad</w:t></w:r></w:hyperlink></w:p>
        """.trimIndent()

        val paragraph = extractDocx(docx(body, rels = rels))[0] as OfficeBlock.Paragraph

        assertEquals("see site and bad", paragraph.text)
        assertEquals(listOf(TextSpan(4, 8, CharFormat(link = "https://example.com/a"))), paragraph.spans)
    }

    @Test
    fun `docx hyperlink written as a simple field is a link`() {
        val body = """
            <w:p><w:fldSimple w:instr=" HYPERLINK &quot;https://example.org/x&quot; "><w:r><w:t>link</w:t></w:r></w:fldSimple></w:p>
        """.trimIndent()

        val paragraph = extractDocx(docx(body))[0] as OfficeBlock.Paragraph

        assertEquals(listOf(TextSpan(0, 4, CharFormat(link = "https://example.org/x"))), paragraph.spans)
    }

    @Test
    fun `docx hyperlink written as a complex field links only its result text`() {
        val body = """
            <w:p>
              <w:r><w:fldChar w:fldCharType="begin"/></w:r>
              <w:r><w:instrText xml:space="preserve"> HYPERLINK "https://example.org/y" </w:instrText></w:r>
              <w:r><w:fldChar w:fldCharType="separate"/></w:r>
              <w:r><w:t>click</w:t></w:r>
              <w:r><w:fldChar w:fldCharType="end"/></w:r>
              <w:r><w:t xml:space="preserve"> now</w:t></w:r>
            </w:p>
        """.trimIndent()

        val paragraph = extractDocx(docx(body))[0] as OfficeBlock.Paragraph

        assertEquals("click now", paragraph.text)
        assertEquals(listOf(TextSpan(0, 5, CharFormat(link = "https://example.org/y"))), paragraph.spans)
    }

    // ---- DOCX: alignment, indentation, headings ----

    @Test
    fun `docx alignment and indentation are read`() {
        val body = """
            <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Centered</w:t></w:r></w:p>
            <w:p><w:pPr><w:ind w:left="720"/></w:pPr><w:r><w:t>Quoted</w:t></w:r></w:p>
            <w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:t>Right</w:t></w:r></w:p>
            <w:p><w:pPr><w:jc w:val="both"/></w:pPr><w:r><w:t>Justified</w:t></w:r></w:p>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.Paragraph("Centered", align = BlockAlign.CENTER),
                OfficeBlock.Paragraph("Quoted", indent = 2),
                OfficeBlock.Paragraph("Right", align = BlockAlign.END),
                OfficeBlock.Paragraph("Justified"),
            ),
            extractDocx(docx(body)),
        )
    }

    @Test
    fun `docx headings come from outline levels and style names, not only from the style id`() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="Kop1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
            <w:style w:type="paragraph" w:styleId="Chapter"><w:name w:val="Chapter"/><w:basedOn w:val="Kop1"/></w:style>
            <w:style w:type="paragraph" w:styleId="TocHead"><w:name w:val="TOC Heading"/><w:basedOn w:val="Kop1"/><w:pPr><w:outlineLvl w:val="9"/></w:pPr></w:style>
            <w:style w:type="paragraph" w:styleId="Kop2"><w:name w:val="heading 2"/></w:style>
        """.trimIndent()
        val body = """
            <w:p><w:pPr><w:pStyle w:val="Kop1"/></w:pPr><w:r><w:t>A</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Chapter"/></w:pPr><w:r><w:t>B</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="TocHead"/></w:pPr><w:r><w:t>C</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Kop2"/></w:pPr><w:r><w:t>D</w:t></w:r></w:p>
            <w:p><w:pPr><w:outlineLvl w:val="2"/></w:pPr><w:r><w:t>E</w:t></w:r></w:p>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.Heading("A", 1),
                OfficeBlock.Heading("B", 1),
                OfficeBlock.Paragraph("C"),
                OfficeBlock.Heading("D", 2),
                OfficeBlock.Heading("E", 3),
            ),
            extractDocx(docx(body, styles = styles)),
        )
    }

    // ---- DOCX: lists ----

    @Test
    fun `docx bullets step down by level and symbol-font glyphs fall back to a plain bullet`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/><w:rPr><w:rFonts w:ascii="Symbol"/></w:rPr></w:lvl>
              <w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/><w:lvlText w:val="o"/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        """.trimIndent()
        val body = item("One") + item("Nested", 1) + item("Two")

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.ListItem("One", "•", 0),
                OfficeBlock.ListItem("Nested", "◦", 1),
                OfficeBlock.ListItem("Two", "•", 0),
            ),
            extractDocx(docx(body, numbering = numbering)),
        )
    }

    @Test
    fun `docx numbered list counts per level and a new item restarts the levels below it`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
              <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1.%2"/></w:lvl>
              <w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%3)"/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        """.trimIndent()
        val body = item("A") + item("B", 1) + item("C", 1) + item("D") + item("E", 1) + item("F", 2)

        val markers = extractDocx(docx(body, numbering = numbering)).map { (it as OfficeBlock.ListItem).marker }

        assertEquals(listOf("1.", "1.1", "1.2", "2.", "2.1", "a)"), markers)
    }

    @Test
    fun `docx list instance with a start override restarts at that number`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="0"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="5"/></w:lvlOverride></w:num>
        """.trimIndent()
        val second = item("C").replace("""<w:numId w:val="1"/>""", """<w:numId w:val="2"/>""")
        val body = item("A") + item("B") + second

        val markers = extractDocx(docx(body, numbering = numbering)).map { (it as OfficeBlock.ListItem).marker }

        assertEquals(listOf("1.", "2.", "5."), markers)
    }

    @Test
    fun `docx numbered heading carries its number in the heading text`() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="H1"><w:name w:val="heading 1"/>
              <w:pPr><w:outlineLvl w:val="0"/><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style>
        """.trimIndent()
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        """.trimIndent()
        val body = """<w:p><w:pPr><w:pStyle w:val="H1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>"""

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Heading("1. Intro", 1)),
            extractDocx(docx(body, styles = styles, numbering = numbering)),
        )
    }

    // ---- DOCX: notes ----

    @Test
    fun `docx footnote leaves a raised marker in the text and the note follows the body`() {
        val body = """
            <w:p><w:r><w:t>Claim</w:t></w:r>
              <w:r><w:footnoteReference w:id="2"/></w:r>
              <w:r><w:t xml:space="preserve"> stands.</w:t></w:r></w:p>
        """.trimIndent()
        val footnotes = """
            <w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>
            <w:footnote w:id="2"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t xml:space="preserve"> Source text.</w:t></w:r></w:p></w:footnote>
        """.trimIndent()

        val blocks = extractDocx(docx(body, footnotes = footnotes))

        assertEquals(3, blocks.size)
        val paragraph = blocks[0] as OfficeBlock.Paragraph
        assertEquals("Claim1 stands.", paragraph.text)
        assertEquals(listOf(TextSpan(5, 6, CharFormat(script = ScriptShift.SUPER))), paragraph.spans)
        assertEquals(OfficeBlock.Divider, blocks[1])
        assertEquals(OfficeBlock.Note("1", "Source text."), blocks[2])
    }

    @Test
    fun `docx endnotes are numbered in roman and follow the footnotes`() {
        val body = """
            <w:p><w:r><w:t>Fact</w:t></w:r><w:r><w:endnoteReference w:id="3"/></w:r>
              <w:r><w:t>.</w:t></w:r><w:r><w:footnoteReference w:id="2"/></w:r></w:p>
        """.trimIndent()
        val footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>Foot.</w:t></w:r></w:p></w:footnote>"""
        val endnotes = """<w:endnote w:id="3"><w:p><w:r><w:t>End.</w:t></w:r></w:p></w:endnote>"""

        val blocks = extractDocx(docx(body, footnotes = footnotes, endnotes = endnotes))

        assertEquals("Facti.1", (blocks[0] as OfficeBlock.Paragraph).text)
        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Divider, OfficeBlock.Note("1", "Foot."), OfficeBlock.Note("i", "End.")),
            blocks.drop(1),
        )
    }

    @Test
    fun `docx footnote reference with no matching note leaves the marker and no empty notes section`() {
        val body = """<w:p><w:r><w:t>Text</w:t></w:r><w:r><w:footnoteReference w:id="9"/></w:r></w:p>"""

        val blocks = extractDocx(docx(body, footnotes = """<w:footnote w:id="2"><w:p><w:r><w:t>Other</w:t></w:r></w:p></w:footnote>"""))

        assertEquals(1, blocks.size)
        assertEquals("Text1", (blocks[0] as OfficeBlock.Paragraph).text)
    }

    // ---- DOCX: tables and images ----

    @Test
    fun `docx cell keeps its shading its formatting and its paragraph alignment`() {
        val body = """
            <w:tbl><w:tr>
              <w:tc><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="D9E2F3"/></w:tcPr>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Total</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:t>42</w:t></w:r></w:p></w:tc>
            </w:tr></w:tbl>
        """.trimIndent()

        val table = extractDocx(docx(body))[0] as OfficeBlock.Table

        assertEquals(
            listOf(
                OfficeCell("Total", 0, 1, 1, listOf(TextSpan(0, 5, bold)), 0xD9E2F3, BlockAlign.START),
                OfficeCell("42", 1, 1, 1, emptyList(), null, BlockAlign.END),
            ),
            table.rows[0],
        )
    }

    @Test
    fun `docx list paragraphs inside a cell carry their marker in the cell text`() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="-"/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
        """.trimIndent()
        val body = """<w:tbl><w:tr><w:tc>${item("one")}${item("two")}</w:tc></w:tr></w:tbl>"""

        val table = extractDocx(docx(body, numbering = numbering))[0] as OfficeBlock.Table

        assertEquals("- one\n- two", table.rows[0][0].text)
    }

    @Test
    fun `docx image carries the size and description the document gave it`() {
        val body = """
            <w:p><w:r><w:drawing><wp:inline>
              <wp:extent cx="914400" cy="914400"/><wp:docPr id="1" name="Pic" descr="A small cat"/>
              <a:graphic><a:graphicData><a:blip r:embed="rId7"/></a:graphicData></a:graphic>
            </wp:inline></w:drawing></w:r></w:p>
        """.trimIndent()

        val blocks = extractDocx(
            docx(body, rels = OfficeFixtures.docxRels("rId7" to "media/pic.png"), media = mapOf("pic.png" to OfficeFixtures.PNG_1X1)),
            temp.newFolder("media"),
        )

        val image = blocks[0] as OfficeBlock.Image
        assertEquals(160f, requireNotNull(image.widthDp), 0.01f)
        assertEquals("A small cat", image.altText)
    }

    @Test
    fun `docx image that states no size or description has neither`() {
        val body = """<w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>"""

        val image = extractDocx(
            docx(body, rels = OfficeFixtures.docxRels("rId7" to "media/pic.png"), media = mapOf("pic.png" to OfficeFixtures.PNG_1X1)),
            temp.newFolder("media"),
        )[0] as OfficeBlock.Image

        assertNull(image.widthDp)
        assertNull(image.altText)
    }

    @Test
    fun `docx with a malformed styles part still yields the text`() {
        val bytes = OfficeFixtures.docx(
            OfficeFixtures.docxBody("""<w:p><w:r><w:t>Survives</w:t></w:r></w:p>"""),
            rawEntries = mapOf("word/styles.xml" to "<w:styles><w:style".toByteArray()),
        )

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Survives")), extractDocx(bytes))
    }

    // ---- ODT ----

    private val odtStyles = """
        <style:style style:name="T1" style:family="text"><style:text-properties fo:font-weight="bold"/></style:style>
        <style:style style:name="T2" style:family="text">
          <style:text-properties fo:font-style="italic" style:text-underline-style="solid" fo:color="#ff0000"/></style:style>
        <style:style style:name="T3" style:family="text"><style:text-properties style:text-position="super 58%"/></style:style>
        <style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
        <style:style style:name="P2" style:family="paragraph"><style:paragraph-properties fo:margin-left="0.5in"/></style:style>
        <style:style style:name="P3" style:family="paragraph" style:parent-style-name="Title"/>
        <style:style style:name="Title" style:family="paragraph"/>
        <style:style style:name="Heading_20_2" style:family="paragraph"/>
    """.trimIndent()

    private fun odt(body: String, styles: String = odtStyles, media: Map<String, ByteArray> = emptyMap()) =
        OfficeFixtures.odt(OfficeFixtures.odtContent(body, styles), media)

    @Test
    fun `odt span styles become spans over the unchanged text`() {
        val body = """
            <text:p>plain <text:span text:style-name="T1">bold</text:span> and
            <text:span text:style-name="T2">styled</text:span><text:span text:style-name="T3">2</text:span></text:p>
        """.trimIndent()

        val paragraph = extractOdt(odt(body))[0] as OfficeBlock.Paragraph

        assertEquals("plain bold and\nstyled2", paragraph.text)
        assertEquals(
            listOf(
                TextSpan(6, 10, bold),
                TextSpan(15, 21, CharFormat(italic = true, underline = true, color = 0xFF0000)),
                TextSpan(21, 22, CharFormat(script = ScriptShift.SUPER)),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun `odt paragraph style supplies alignment and indentation`() {
        val body = """
            <text:p text:style-name="P1">Centered</text:p>
            <text:p text:style-name="P2">Quoted</text:p>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.Paragraph("Centered", align = BlockAlign.CENTER),
                OfficeBlock.Paragraph("Quoted", indent = 2),
            ),
            extractOdt(odt(body)),
        )
    }

    @Test
    fun `odt named styles in styles xml apply to content xml`() {
        val named = """
            <office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
              <office:styles>
                <style:style style:name="Quote" style:family="paragraph"><style:text-properties fo:font-style="italic"/></style:style>
              </office:styles>
            </office:document-styles>
        """.trimIndent()
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent("""<text:p text:style-name="Quote">Said.</text:p>"""),
            extra = mapOf("styles.xml" to named.toByteArray()),
        )

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Paragraph("Said.", spans = listOf(TextSpan(0, 5, italic)))),
            extractOdt(bytes),
        )
    }

    @Test
    fun `odt hyperlink text carries its target`() {
        val body = """<text:p>see <text:a xlink:href="https://example.com/p">site</text:a> and <text:a xlink:href="javascript:x">bad</text:a></text:p>"""

        val paragraph = extractOdt(odt(body))[0] as OfficeBlock.Paragraph

        assertEquals("see site and bad", paragraph.text)
        assertEquals(listOf(TextSpan(4, 8, CharFormat(link = "https://example.com/p"))), paragraph.spans)
    }

    @Test
    fun `odt title and heading styles on a plain paragraph make it a heading`() {
        val body = """
            <text:p text:style-name="P3">My Doc</text:p>
            <text:p text:style-name="Heading_20_2">Part</text:p>
            <text:p>Body</text:p>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Heading("My Doc", 1), OfficeBlock.Heading("Part", 2), OfficeBlock.Paragraph("Body")),
            extractOdt(odt(body)),
        )
    }

    @Test
    fun `odt bullets and numbers follow the list style through nesting`() {
        val styles = """
            <text:list-style style:name="L1">
              <text:list-level-style-bullet text:level="1" text:bullet-char="•"/>
              <text:list-level-style-number text:level="2" style:num-format="1" style:num-suffix="."/>
            </text:list-style>
        """.trimIndent()
        val body = """
            <text:list text:style-name="L1">
              <text:list-item><text:p>One</text:p>
                <text:list>
                  <text:list-item><text:p>Nested</text:p></text:list-item>
                  <text:list-item><text:p>Nested two</text:p></text:list-item>
                </text:list>
              </text:list-item>
              <text:list-item><text:p>Two</text:p></text:list-item>
            </text:list>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.ListItem("One", "•", 0),
                OfficeBlock.ListItem("Nested", "1.", 1),
                OfficeBlock.ListItem("Nested two", "2.", 1),
                OfficeBlock.ListItem("Two", "•", 0),
            ),
            extractOdt(odt(body, styles)),
        )
    }

    @Test
    fun `odt second paragraph of a list item is a continuation without a marker`() {
        val styles = """
            <text:list-style style:name="L1">
              <text:list-level-style-number text:level="1" style:num-format="a" style:num-suffix=")"/>
            </text:list-style>
        """.trimIndent()
        val body = """
            <text:list text:style-name="L1">
              <text:list-item><text:p>First</text:p><text:p>More</text:p></text:list-item>
              <text:list-item><text:p>Second</text:p></text:list-item>
            </text:list>
        """.trimIndent()

        assertEquals(
            listOf<OfficeBlock>(
                OfficeBlock.ListItem("First", "a)", 0),
                OfficeBlock.ListItem("More", "", 0),
                OfficeBlock.ListItem("Second", "b)", 0),
            ),
            extractOdt(odt(body, styles)),
        )
    }

    @Test
    fun `odt endnote keeps the label the document chose and follows the footnotes`() {
        val body = """
            <text:p>A<text:note text:note-class="endnote"><text:note-citation>i</text:note-citation>
              <text:note-body><text:p>End.</text:p></text:note-body></text:note>
            B<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation>
              <text:note-body><text:p>Foot.</text:p></text:note-body></text:note></text:p>
        """.trimIndent()

        val blocks = extractOdt(odt(body))

        assertEquals(
            listOf<OfficeBlock>(OfficeBlock.Divider, OfficeBlock.Note("1", "Foot."), OfficeBlock.Note("i", "End.")),
            blocks.drop(1),
        )
    }

    @Test
    fun `odt note body keeps formatting inside the note and the host keeps its own afterwards`() {
        val body = """
            <text:p><text:span text:style-name="T1">Bold<text:note text:note-class="footnote"><text:note-citation>1</text:note-citation>
              <text:note-body><text:p>Plain note.</text:p></text:note-body></text:note>still bold</text:span></text:p>
        """.trimIndent()

        val blocks = extractOdt(odt(body))

        val paragraph = blocks[0] as OfficeBlock.Paragraph
        assertEquals("Bold1still bold", paragraph.text)
        assertEquals(OfficeBlock.Note("1", "Plain note."), blocks[2])
        // The note's text did not inherit the span it was written inside, and the host's bold resumed after it.
        assertEquals(bold, paragraph.spans.first().format)
        assertEquals(TextSpan(0, 4, bold), paragraph.spans.first())
        assertEquals(TextSpan(5, 15, bold), paragraph.spans.last())
    }

    @Test
    fun `odt cell keeps its fill and its formatting`() {
        val styles = odtStyles + """<style:style style:name="ce1" style:family="table-cell"><style:table-cell-properties fo:background-color="#ffff00"/></style:style>"""
        val body = """
            <table:table><table:table-column/><table:table-row>
              <table:table-cell table:style-name="ce1"><text:p><text:span text:style-name="T1">Hot</text:span></text:p></table:table-cell>
            </table:table-row></table:table>
        """.trimIndent()

        val table = extractOdt(odt(body, styles))[0] as OfficeBlock.Table

        assertEquals(
            listOf(OfficeCell("Hot", 0, 1, 1, listOf(TextSpan(0, 3, bold)), 0xFFFF00, BlockAlign.START)),
            table.rows[0],
        )
    }

    @Test
    fun `odt image carries its size and description and the description stays out of the text`() {
        val body = """
            <text:p><draw:frame svg:width="2in"><draw:image xlink:href="Pictures/a.png"/><svg:desc>A cat</svg:desc></draw:frame></text:p>
        """.trimIndent()

        val blocks = extractOdt(odt(body, media = mapOf("a.png" to OfficeFixtures.PNG_1X1)), temp.newFolder("media"))

        assertEquals(1, blocks.size)
        val image = blocks[0] as OfficeBlock.Image
        assertEquals(320f, requireNotNull(image.widthDp), 0.01f)
        assertEquals("A cat", image.altText)
        assertNotNull(image.file)
    }

    private companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    }
}
