package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The binary and structured formats, each through its own reader: PDF, Word, OpenDocument, EPUB and CSV. */
@RunWith(AndroidJUnit4::class)
class OpenDocumentFormatsTest {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aPdfOpensWithItsPagesDrawn() {
        app.openFromAnotherApp(Fixtures.bytes("sample.pdf", Documents.pdf("alpha", "bravo", "charlie")))

        app.assertShown(app.text("sample.pdf"), load)
        app.assertShown(app.descStartsWith("Page 1"), load)
    }

    @Test
    fun aWordDocumentOpensWithItsHeadingTextAndTable() {
        val body = Documents.docxParagraph("Annual report", "Heading1") +
            Documents.docxParagraph("Revenue rose in every region.") +
            Documents.docxTable(listOf(listOf("Region", "Growth"), listOf("North", "12%")))
        app.openFromAnotherApp(Fixtures.bytes("report.docx", Documents.docx(body)))

        app.assertShown(app.label("Annual report"), load)
        app.assertShown(app.label("Revenue rose in every region."), load)
        app.assertShown(app.label("Region"), load)
        app.assertShown(app.label("North"), load)
        app.assertShown(app.label("12%"), load)
    }

    @Test
    fun aWordDocumentWithNothingToReadSaysSo() {
        app.openFromAnotherApp(Fixtures.bytes("blank.docx", Documents.docx("")))

        app.assertShown(app.text("No readable text found in this document."), load)
        app.assertShown(app.text("Back"))
    }

    @Test
    fun anOpenDocumentTextFileOpens() {
        val body = Documents.odtHeading("Meeting minutes") + Documents.odtParagraph("Attendees agreed to ship on Friday.")
        app.openFromAnotherApp(Fixtures.bytes("minutes.odt", Documents.odt(body)))

        app.assertShown(app.label("Meeting minutes"), load)
        app.assertShown(app.label("Attendees agreed to ship on Friday."), load)
    }

    @Test
    fun aBookOpensAndItsContentsJumpToAChapter() {
        val filler = (1..60).joinToString("") { "<p>Filler paragraph $it.</p>" }
        val book = Documents.epub(
            chapters = listOf(
                Documents.Chapter("one.xhtml", "<h1>The First Chapter</h1>$filler"),
                Documents.Chapter("two.xhtml", "<h1>The Second Chapter</h1><p>The ending.</p>"),
            ),
            contents = listOf("Start here" to "one.xhtml", "Jump here" to "two.xhtml"),
        )
        app.openFromAnotherApp(Fixtures.bytes("story.epub", book))
        app.assertShown(app.label("The First Chapter"), load)
        // Far enough down that the next chapter has not been drawn yet.
        app.assertGone(app.label("The Second Chapter"))

        app.chooseFromViewerMenu("Outline")
        app.tap(app.text("Jump here"))

        app.assertShown(app.label("The Second Chapter"), load)
        app.assertGone(app.label("The First Chapter"))
    }

    @Test
    fun aCsvFileOpensAsATable() {
        app.openFromAnotherApp(Fixtures.text("people.csv", "name,age\nAlice,30\nBob,25\n"))

        app.assertShown(app.label("Alice"), load)
        app.assertShown(app.label("30"), load)
        app.assertShown(app.label("Bob"), load)
    }

    @Test
    fun aCorruptPdfFailsWithAWayBack() {
        app.openFromAnotherApp(Fixtures.text("broken.pdf", "this is not a pdf"))

        app.assertShown(app.text("Back"), load)
        app.assertGone(app.descStartsWith("Page"))
    }
}
