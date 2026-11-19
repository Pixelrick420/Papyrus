package io.github.pixelrick420.papyrus.viewer.pptx

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pixelrick420.papyrus.viewer.pptx.render.GoldenDecks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One deck, several widths: the page source scales one width-independent display list, it does not re-lay out. */
@RunWith(AndroidJUnit4::class)
class SlidePageSourceWidthsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dimensionsFollowTheSlideSizeAtEveryWidth() = runBlocking {
        openShapeZoo().use { source ->
            val aspect = source.loadAspectRatios().single()
            for (width in WIDTHS) {
                val bitmap = source.render(0, width)
                assertNotNull("no render at $width px", bitmap)
                bitmap ?: continue
                assertEquals("width at $width px", width, bitmap.width)
                assertEquals("height at $width px", (width * aspect).roundToInt(), bitmap.height)
            }
        }
    }

    @Test
    fun smallerWidthIsTheDownscaledLargerRender() = runBlocking {
        openShapeZoo().use { source ->
            val large = source.render(0, LARGE_WIDTH) ?: error("no render at $LARGE_WIDTH px")
            val small = source.render(0, LARGE_WIDTH / 2) ?: error("no render at ${LARGE_WIDTH / 2} px")
            val scaled = Bitmap.createScaledBitmap(large, small.width, small.height, true)
            val expected = IntArray(scaled.width * scaled.height)
            val actual = IntArray(scaled.width * scaled.height)
            scaled.getPixels(expected, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            small.getPixels(actual, 0, small.width, 0, 0, small.width, small.height)
            scaled.recycle()
            var mismatched = 0
            for (index in expected.indices) {
                if (channelDistance(expected[index], actual[index]) > CHANNEL_TOLERANCE) mismatched++
            }
            val share = mismatched.toDouble() / expected.size
            assertTrue("$mismatched pixels (%.2f%%) differ".format(share * 100), share <= MAX_MISMATCH_SHARE)
        }
    }

    private suspend fun openShapeZoo(): SlidePageSource {
        val deck = GoldenDecks.all.single { it.name == "shape-zoo" }
        val media = File(context.cacheDir, "widths-media").apply {
            deleteRecursively()
            mkdirs()
        }
        return SlidePageSource.openStream(
            open = { deck.bytes.inputStream() },
            mediaDir = media,
            presets = PresetGeometry(open = { context.assets.open("ooxml/presetShapeDefinitions.xml") }),
        )
    }

    private inline fun <T> SlidePageSource.use(block: (SlidePageSource) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun channelDistance(first: Int, second: Int): Int {
        val alpha = abs(((first ushr 24) and 0xFF) - ((second ushr 24) and 0xFF))
        val red = abs(((first ushr 16) and 0xFF) - ((second ushr 16) and 0xFF))
        val green = abs(((first ushr 8) and 0xFF) - ((second ushr 8) and 0xFF))
        val blue = abs((first and 0xFF) - (second and 0xFF))
        return max(max(alpha, red), max(green, blue))
    }

    private companion object {
        val WIDTHS = listOf(320, 640, 1280)
        const val LARGE_WIDTH = 1280
        const val CHANNEL_TOLERANCE = 16
        const val MAX_MISMATCH_SHARE = 0.02
    }
}
