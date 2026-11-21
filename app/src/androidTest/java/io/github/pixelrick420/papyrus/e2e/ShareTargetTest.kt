package io.github.pixelrick420.papyrus.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pixelrick420.papyrus.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareTargetTest : E2eTest() {

    private val app = AppDriver()

    @Test
    fun aSharedPdfOpensTheViewer() {
        val fixture = Fixtures.bytes("shared.pdf", Documents.pdf("shared text"))
        val intent = sendIntent("application/pdf").apply {
            putExtra(Intent.EXTRA_STREAM, fixture.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            app.assertShown(app.text("shared.pdf"), AppDriver.LOAD_TIMEOUT_MS)
            app.assertShown(app.label("shared text"), AppDriver.LOAD_TIMEOUT_MS)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun sharedTextWithoutAStreamLeavesTheLibraryAlone() {
        val intent = sendIntent("text/plain").apply { putExtra(Intent.EXTRA_TEXT, "just words") }

        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            app.assertShown(app.text("No documents yet"), AppDriver.LOAD_TIMEOUT_MS)
        } finally {
            scenario.close()
        }
    }

    private fun sendIntent(type: String): Intent =
        Intent(Intent.ACTION_SEND, null, InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .setType(type)
}
