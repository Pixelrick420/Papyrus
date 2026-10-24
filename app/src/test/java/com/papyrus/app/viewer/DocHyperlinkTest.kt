package com.papyrus.app.viewer

import com.papyrus.app.viewer.DocRichFixtures.CFBOLD
import com.papyrus.app.viewer.DocRichFixtures.Para
import com.papyrus.app.viewer.DocRichFixtures.Run
import com.papyrus.app.viewer.DocRichFixtures.sprm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocHyperlinkTest {

    private fun extract(bytes: ByteArray) = DocTextExtractor.extract(DocFixtures.streamFactory(bytes))

    private fun paragraph(text: String): OfficeBlock.Paragraph = extract(DocFixtures.doc(text)).single() as OfficeBlock.Paragraph

    private fun link(url: String) = CharFormat(link = url)

    /** `0x13 instruction 0x14 shown 0x15`, how Word stores a hyperlink. */
    private fun field(instruction: String, shown: String) = "\u0013$instruction\u0014$shown\u0015"

    private val bold = CharFormat(bold = true)

    @Test
    fun aHyperlinkFieldLinksItsResult() {
        val block = paragraph("see " + field(" HYPERLINK \"https://example.com/a\" ", "the site") + " now")
        assertEquals(OfficeBlock.Paragraph("see the site now", listOf(TextSpan(4, 12, link("https://example.com/a")))), block)
    }

    @Test
    fun mailAndPhoneLinksAreLinks() {
        val block = paragraph(
            field("HYPERLINK \"mailto:a@b.com\"", "mail") + " " + field("HYPERLINK \"tel:+15551234\"", "call"),
        )
        assertEquals(
            listOf(TextSpan(0, 4, link("mailto:a@b.com")), TextSpan(5, 9, link("tel:+15551234"))),
            block.spans,
        )
    }

    @Test
    fun theTargetMayBeBareAndSwitchesAreSkipped() {
        val bare = paragraph(field("HYPERLINK https://example.com/b", "bare"))
        assertEquals(listOf(TextSpan(0, 4, link("https://example.com/b"))), bare.spans)
        val withSwitches = paragraph(field("HYPERLINK \"https://example.com/c\" \\o \"A tip\" \\t \"_blank\"", "tip"))
        assertEquals(listOf(TextSpan(0, 3, link("https://example.com/c"))), withSwitches.spans)
        val switchesFirst = paragraph(field("hyperlink \\m \\t \"_top\" \"HTTP://EXAMPLE.COM/d\"", "first"))
        assertEquals(listOf(TextSpan(0, 5, link("HTTP://EXAMPLE.COM/d"))), switchesFirst.spans)
    }

    @Test
    fun onlySafeSchemesBecomeLinks() {
        for (target in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "content://x/y", "data:text/html,x", "/relative", "page.html")) {
            val block = paragraph(field("HYPERLINK \"$target\"", "label"))
            assertEquals("label", block.text)
            assertEquals("no link for $target", emptyList<TextSpan>(), block.spans)
        }
    }

    @Test
    fun aLinkToAPlaceInTheDocumentLinksNowhere() {
        val block = paragraph(field("HYPERLINK \\l \"section1\"", "jump"))
        assertEquals(OfficeBlock.Paragraph("jump"), block)
    }

    @Test
    fun otherFieldsAreNotLinks() {
        assertEquals(OfficeBlock.Paragraph("3"), paragraph(field("PAGE \\* MERGEFORMAT", "3")))
        assertEquals(OfficeBlock.Paragraph("x"), paragraph(field("HYPERLINKED \"https://example.com\"", "x")))
        assertEquals(OfficeBlock.Paragraph("x"), paragraph(field("HYPERLINK", "x")))
    }

    @Test
    fun theCharacterStyleOfALinkSurvivesAlongsideTheLink() {
        val runs = listOf(Run("\u0013 HYPERLINK \"https://example.com\" \u0014"), Run("bold link", sprm(CFBOLD, 1)), Run(" plain\u0015"))
        val block = extract(DocRichFixtures.doc(listOf(Para(runs)))).single()
        assertEquals(
            OfficeBlock.Paragraph(
                "bold link plain",
                listOf(TextSpan(0, 9, bold.copy(link = "https://example.com")), TextSpan(9, 15, link("https://example.com"))),
            ),
            block,
        )
    }

    @Test
    fun aFieldInsideALinksResultStaysPartOfTheLink() {
        val nested = field("HYPERLINK \"https://example.com\"", "see page " + field("PAGEREF x", "7") + " for more")
        val block = paragraph("a $nested b")
        assertEquals(OfficeBlock.Paragraph("a see page 7 for more b", listOf(TextSpan(2, 21, link("https://example.com")))), block)
    }

    @Test
    fun aLinkEndsWhereItsFieldDoes() {
        val block = paragraph(field("HYPERLINK \"https://one.example\"", "one") + field("HYPERLINK \"https://two.example\"", "two") + "three")
        assertEquals(
            listOf(TextSpan(0, 3, link("https://one.example")), TextSpan(3, 6, link("https://two.example"))),
            block.spans,
        )
    }

    @Test
    fun anInstructionPastTheLimitMakesNoLinkAndNothingElseBreaks() {
        val huge = "https://example.com/" + "a".repeat(DocLinks.MAX_INSTRUCTION + 100)
        val block = paragraph("before " + field("HYPERLINK \"$huge\"", "shown") + " after")
        assertEquals(OfficeBlock.Paragraph("before shown after"), block)
    }

    @Test
    fun anUnclosedFieldDoesNotLinkTheRest() {
        val block = paragraph("start \u0013 HYPERLINK \"https://example.com\"")
        assertEquals(OfficeBlock.Paragraph("start "), block)
    }

    @Test
    fun addressesWrittenOutAreLinked() {
        val block = paragraph("Go to https://example.com/page, or mail mailto:a@b.com or tel:+15551234.")
        assertEquals(
            listOf(
                TextSpan(6, 30, link("https://example.com/page")),
                TextSpan(40, 54, link("mailto:a@b.com")),
                TextSpan(58, 71, link("tel:+15551234")),
            ),
            block.spans,
        )
    }

    @Test
    fun theEndOfASentenceOrABracketIsNotPartOfTheAddress() {
        val block = paragraph("(see https://example.com/x). Also http://example.com/a_(b) and \"http://q.example/\"!")
        assertEquals(
            listOf(
                TextSpan(5, 26, link("https://example.com/x")),
                TextSpan(34, 58, link("http://example.com/a_(b)")),
                TextSpan(64, 81, link("http://q.example/")),
            ),
            block.spans,
        )
    }

    @Test
    fun onlyTheFourSchemesAreRecognised() {
        val text = "www.example.com ftp://example.com/f javascript:alert(1) xhttp://example.com a@b.com https:// mailto: tel:"
        assertEquals(emptyList<TextSpan>(), paragraph(text).spans)
    }

    @Test
    fun schemesAreCaseInsensitive() {
        val block = paragraph("HTTPS://EXAMPLE.COM/Up")
        assertEquals(listOf(TextSpan(0, 22, link("HTTPS://EXAMPLE.COM/Up"))), block.spans)
    }

    @Test
    fun aWrittenOutAddressKeepsTheFormattingItAlreadyHas() {
        val runs = listOf(Run("go to "), Run("https://example.com/b", sprm(CFBOLD, 1)), Run(" or "), Run("http://x.example/", sprm(CFBOLD, 1)))
        val block = extract(DocRichFixtures.doc(listOf(Para(runs)))).single()
        assertEquals(
            listOf(
                TextSpan(6, 27, bold.copy(link = "https://example.com/b")),
                TextSpan(31, 48, bold.copy(link = "http://x.example/")),
            ),
            (block as OfficeBlock.Paragraph).spans,
        )
    }

    @Test
    fun anAddressCutByAFormatChangeIsOneLinkInTwoSpans() {
        val runs = listOf(Run("https://exa"), Run("mple.com/z", sprm(CFBOLD, 1)))
        val block = extract(DocRichFixtures.doc(listOf(Para(runs)))).single() as OfficeBlock.Paragraph
        assertEquals(
            listOf(TextSpan(0, 11, link("https://example.com/z")), TextSpan(11, 21, bold.copy(link = "https://example.com/z"))),
            block.spans,
        )
    }

    @Test
    fun anAddressInsideAFieldLinkKeepsTheFieldsTarget() {
        val block = paragraph(field("HYPERLINK \"https://real.example/\"", "https://shown.example/"))
        assertEquals(listOf(TextSpan(0, 22, link("https://real.example/"))), block.spans)
    }

    @Test
    fun linksReachHeadingsListItemsAndCells() {
        val runs = listOf(Run("see https://example.com/h"))
        val numbered = DocRichFixtures.numbered(10)
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    Para(runs, istd = 1, papx = DocRichFixtures.sprmWord(DocRichFixtures.PILFO, 1)),
                    DocRichFixtures.item("go to http://example.com/l", 1),
                    Para("http://example.com/c", mark = '\u0007'),
                    Para("", mark = '\u0007'),
                ),
                lists = listOf(numbered),
                instances = listOf(DocRichFixtures.ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.Heading("1. see https://example.com/h", 1, listOf(TextSpan(7, 28, link("https://example.com/h")))),
                OfficeBlock.ListItem("go to http://example.com/l", "2.", 0, listOf(TextSpan(6, 26, link("http://example.com/l")))),
                OfficeBlock.Table(
                    listOf(listOf(OfficeCell("http://example.com/c", column = 0, spans = listOf(TextSpan(0, 20, link("http://example.com/c")))))),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun theTargetParserNeverThrows() {
        for (text in listOf("", " ", "HYPERLINK", "HYPERLINK \"", "HYPERLINK \\", "HYPERLINK \\l", "HYPERLINK \\o \"x", "hyperlink \"\"", "HYPERLINK \\\\ \\\\")) {
            assertNull(text, DocLinks.hyperlinkTarget(text))
        }
    }

    @Test
    fun overlayHandlesLinksAtTheEdgesOfSpans() {
        val spans = listOf(TextSpan(0, 5, CharFormat(bold = true)), TextSpan(10, 15, CharFormat(italic = true)))
        val links = listOf(LinkRange(3, 12, "https://x.example"), LinkRange(20, 25, "https://y.example"))
        assertEquals(
            listOf(
                TextSpan(0, 3, CharFormat(bold = true)),
                TextSpan(3, 5, CharFormat(bold = true, link = "https://x.example")),
                TextSpan(5, 10, link("https://x.example")),
                TextSpan(10, 12, CharFormat(italic = true, link = "https://x.example")),
                TextSpan(12, 15, CharFormat(italic = true)),
                TextSpan(20, 25, link("https://y.example")),
            ),
            DocLinks.overlay(spans, links),
        )
    }
}
