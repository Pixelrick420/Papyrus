package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** What the viewer's menu offers on a document that is open: find, go to, and file info. */
@RunWith(AndroidJUnit4::class)
class ViewerToolsTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun findStepsThroughTheMatchesOfATextFile() {
        app.openFromAnotherApp(Fixtures.text("notes.txt", "first needle here\nsome filler\nsecond needle there\n"))
        app.assertShown(app.label("first needle here"), load)

        app.chooseFromViewerMenu("Find in file")
        app.typeInto(app.field(), "needle")
        app.assertShown(app.text("1 of 2"))
        app.tap(app.desc("Next match"))
        app.assertShown(app.text("2 of 2"))

        app.typeInto(app.field(), "absent")
        app.assertShown(app.text("No matches"))

        app.tap(app.desc("Close find"))
        app.assertGone(app.field())
    }

    @Test
    fun findLooksInsideAPdf() {
        app.openFromAnotherApp(Fixtures.bytes("sample.pdf", Documents.pdf("alpha", "bravo", "charlie")))
        app.assertShown(app.descStartsWith("Page 1"), load)

        app.chooseFromViewerMenu("Find in file")
        app.typeInto(app.field(), "charlie")

        app.assertShown(app.text("1 of 1"), load)
    }

    @Test
    fun goToLineJumpsFarThroughALongFile() {
        val lines = (1..600).joinToString("\n") { "row-${it.toString().padStart(3, '0')} marker" }
        app.openFromAnotherApp(Fixtures.text("long.txt", lines))
        app.assertShown(app.label("row-001 marker"), load)

        app.chooseFromViewerMenu("Go to line")
        app.typeInto(app.field(), "450")
        app.tap(app.text("Go"))

        app.assertShown(app.label("row-450 marker"), load)
        app.assertGone(app.label("row-001 marker"))
    }

    @Test
    fun goToPageJumpsToTheLastPageOfAPdf() {
        val pages = (1..8).map { "page number $it" }.toTypedArray()
        app.openFromAnotherApp(Fixtures.bytes("long.pdf", Documents.pdf(*pages)))
        app.assertShown(app.descStartsWith("Page 1"), load)

        app.chooseFromViewerMenu("Go to page")
        app.typeInto(app.field(), "8")
        app.tap(app.text("Go"))

        app.assertShown(app.descStartsWith("Page 8"), load)
        app.assertGone(app.descStartsWith("Page 1"))
    }

    @Test
    fun fileInfoNamesTheFormatOfATextFile() {
        app.openFromAnotherApp(Fixtures.text("notes.txt", "Hello"))
        app.assertShown(app.label("Hello"), load)

        app.chooseFromViewerMenu("File info")

        app.assertShown(app.text("Format"))
        app.assertShown(app.text("TXT"))
        app.assertShown(app.text("Size"))
    }

    @Test
    fun fileInfoCountsThePagesOfAPdf() {
        app.openFromAnotherApp(Fixtures.bytes("sample.pdf", Documents.pdf("alpha", "bravo", "charlie")))
        app.assertShown(app.descStartsWith("Page 1"), load)

        app.chooseFromViewerMenu("File info")

        app.assertShown(app.text("PDF"))
        app.assertShown(app.text("Pages"))
        app.assertShown(app.text("3"))
    }
}
