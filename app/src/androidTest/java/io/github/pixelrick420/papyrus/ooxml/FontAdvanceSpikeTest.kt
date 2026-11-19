package io.github.pixelrick420.papyrus.ooxml

import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Spike S2: AWT and Paint must agree on unhinted advances for the same bundled TTF, and Android
 * line breaks must be stable across widths and densities.
 *
 * <p>The golden advances come from `tools/pptx/spikes/AwtAdvanceDump.java`; regenerate it there and
 * commit the result.
 */
@RunWith(AndroidJUnit4::class)
class FontAdvanceSpikeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun paint_advances_match_awt_within_a_fiftieth_of_a_point() {
        val golden = JSONObject(
            instrumentation.context.assets.open(GOLDEN).use { it.readBytes() }.decodeToString(),
        )
        val sizePx = golden.getDouble("sizePx").toFloat()
        val chars = golden.getString("chars")
        val expected = golden.getJSONArray("advances")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.createFromAsset(instrumentation.targetContext.assets, FONT)
            textSize = sizePx
            isLinearText = true
        }

        var worst = 0f
        var worstChar = ' '
        for (i in chars.indices) {
            val actual = paint.measureText(chars, i, i + 1)
            val delta = abs(actual - expected.getDouble(i).toFloat())
            if (delta > worst) {
                worst = delta
                worstChar = chars[i]
            }
        }
        assertTrue("'$worstChar' is off by $worst px", worst <= TOLERANCE_PX)
    }

    @Test
    fun line_breaks_are_identical_at_three_widths_and_two_densities() {
        val text = PARAGRAPH.repeat(3)
        for (widthDp in WIDTHS_DP) {
            val single = breaks(text, widthDp, density = 1f)
            assertTrue("width ${widthDp}dp did not wrap", single.size > 1)
            for (density in DENSITIES) {
                assertEquals(
                    "width ${widthDp}dp at density $density",
                    single,
                    breaks(text, widthDp, density),
                )
            }
        }
    }

    private fun breaks(text: String, widthDp: Float, density: Float): List<Int> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.createFromAsset(instrumentation.targetContext.assets, FONT)
            textSize = TEXT_SIZE_SP * density
            isLinearText = true
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, (widthDp * density).toInt())
            .build()
        return (0 until layout.lineCount).flatMap {
            listOf(layout.getLineStart(it), layout.getLineEnd(it))
        }
    }

    private companion object {
        const val FONT = "ooxml/fonts/humanist/PptxHumanist-Regular.ttf"
        const val GOLDEN = "spikes/awt-advances.json"
        // Devices snap advances to whole pixels where AWT keeps fractions; a fallback font is off by several.
        const val TOLERANCE_PX = 1f
        const val TEXT_SIZE_SP = 16f
        val WIDTHS_DP = listOf(180f, 280f, 420f)
        val DENSITIES = listOf(1f, 2f)
        const val PARAGRAPH =
            "The quick brown fox jumps over the lazy dog while twelve wizards vex a quiet nymph. " +
                "Metrics decide where lines break, so a font that keeps its advances keeps its " +
                "layout, and a document authored elsewhere still looks like the author left it. "
    }
}
