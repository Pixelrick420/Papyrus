package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeTextFrameTest : E2eTest() {

    private val app = AppDriver()
    private val packageName = app.context.packageName
    private val lines = 50 * 1024 * 1024 / LINE_BYTES

    @Test
    fun jumpingToTheEndOfA50MbFileKeepsFramesWithinBudget() {
        app.openFromAnotherApp(Fixtures.largeText("huge.txt", lines, LINE_BYTES))
        app.assertShown(app.label("row-00000001"), AppDriver.LOAD_TIMEOUT_MS)

        Shell.run("dumpsys gfxinfo $packageName reset")
        app.chooseFromViewerMenu("Go to line")
        app.typeInto(app.field(), lines.toString())
        app.tap(app.text("Go"))
        app.assertShown(app.label(lastLabel()), AppDriver.LOAD_TIMEOUT_MS)

        val p95 = FrameBudget.percentile95Ms(packageName)
        assertTrue("95th percentile frame was ${p95}ms", p95 != null && p95 <= FRAME_BUDGET_MS)
    }

    private fun lastLabel(): String = "row-${lines.toString().padStart(8, '0')}"

    private companion object {
        const val LINE_BYTES = 64
        const val FRAME_BUDGET_MS = 50
    }
}
