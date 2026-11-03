package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text formats handed over by another app, the way a file manager opens them: no picker, no library. */
@RunWith(AndroidJUnit4::class)
class OpenTextDocumentsTest {

    private val app = AppDriver()

    @Test
    fun aTextFileOpensFromAnotherApp() {
        app.openFromAnotherApp(Fixtures.text("notes.txt", "Hello from another app\nsecond line"))

        app.assertShown(app.text("notes.txt"), AppDriver.LOAD_TIMEOUT_MS)
        app.assertShown(app.label("Hello from another app"), AppDriver.LOAD_TIMEOUT_MS)
    }

    @Test
    fun backFromADocumentAnotherAppOpenedLeavesPapyrus() {
        app.openFromAnotherApp(Fixtures.text("notes.txt", "Hello from another app"))
        app.assertShown(app.label("Hello from another app"), AppDriver.LOAD_TIMEOUT_MS)

        app.back()

        // There is no Home underneath a handed-over document to land on, so the app closes with it.
        app.assertGone(app.anything(), AppDriver.UI_TIMEOUT_MS)
    }

    @Test
    fun markdownIsRenderedInsteadOfShownAsSource() {
        app.openFromAnotherApp(Fixtures.text("report.md", MARKDOWN))

        // The whole document is one text view, not a Compose `Text`, so one node holds everything drawn as text.
        val shown = app.find(app.textContains("Quarterly report"), AppDriver.LOAD_TIMEOUT_MS).text
        assertTrue(shown, shown.contains("Revenue grew 12% this quarter, see the full notes."))
        assertTrue(shown, shown.contains("Cancelled item"))
        assertTrue(shown, shown.contains("Energy follows"))
        assertTrue(shown, shown.contains("as everyone knows."))
        assertTrue(shown, shown.contains("The footnote body."))
        assertTrue(shown, shown.contains("Footnotes"))
        // Math is the one construct that keeps its source: RaTeX draws the formula over the `$$...$$` text with a
        // replacement span instead of rewriting the string, so those characters stay in the node and only the
        // drawing changes. Every other plugin rewrites the text, so its markup is gone when it ran.
        for (markup in listOf("# ", "**", "](", "~~", "[^")) {
            assertFalse("\"$markup\" is still in: $shown", shown.contains(markup))
        }
    }

    @Test
    fun jsonIsPrettyPrintedWhenItOpens() {
        app.openFromAnotherApp(Fixtures.text("config.json", """{"name":"papyrus","kind":"viewer"}"""))

        app.assertShown(app.label("\"name\": \"papyrus\""), AppDriver.LOAD_TIMEOUT_MS)
        app.assertGone(app.label("\"name\":\"papyrus\""))
    }

    @Test
    fun sourceCodeOpensAsCode() {
        app.openFromAnotherApp(Fixtures.text("Main.kt", "fun main() {\n    println(\"hello\")\n}\n"))

        app.assertShown(app.text("Main.kt"), AppDriver.LOAD_TIMEOUT_MS)
        app.assertShown(app.label("println(\"hello\")"), AppDriver.LOAD_TIMEOUT_MS)
    }

    @Test
    fun aFileThatIsNotTextIsReportedAsUnsupported() {
        app.openFromAnotherApp(Fixtures.bytes("blob.bin", ByteArray(64) { it.toByte() }))

        app.assertShown(app.text("This file type isn't supported."), AppDriver.LOAD_TIMEOUT_MS)
        app.tap(app.text("Back"))
        app.assertGone(app.anything(), AppDriver.UI_TIMEOUT_MS)
    }

    private companion object {
        /** Every construct the renderer has a plugin for, so a plugin R8 broke shows up as markup left in the text. */
        val MARKDOWN = """
            # Quarterly report

            Revenue grew **12%** this quarter, see the [full notes](https://example.com/notes).

            - ~~Cancelled item~~
            - Shipped item
            - [ ] Open task
            - [x] Done task

            Energy follows ${'$'}${'$'}E = mc^2${'$'}${'$'} as everyone knows.

            ${'$'}${'$'}
            \frac{a}{b}
            ${'$'}${'$'}

            A claim that needs a source.[^1]

            [^1]: The footnote body.
        """.trimIndent()
    }
}
