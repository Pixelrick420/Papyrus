package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class AutofitTest {

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(),
        breaker = LineBreaker { text, _ -> wordBreaks(text) },
    )

    @Test
    fun `the stored font scale shrinks every run`() {
        val line = layout(TextAutofit.Normal(fontScale = 62.5, lineSpacingReduction = 0.0)).lines.single()

        assertEquals(11.0, line.runs.single().sizePt, EPSILON)
        assertEquals(13.2, line.heightPt, EPSILON)
    }

    @Test
    fun `a scaled size is rounded to whole points`() {
        val line = layout(
            TextAutofit.Normal(fontScale = 50.0, lineSpacingReduction = 0.0),
            sizePt = 15.0,
        ).lines.single()

        assertEquals(8.0, line.runs.single().sizePt, EPSILON)
    }

    @Test
    fun `a bare normal autofit leaves the stored size alone`() {
        val line = layout(TextAutofit.Normal(fontScale = 100.0, lineSpacingReduction = 0.0)).lines.single()

        assertEquals(18.0, line.runs.single().sizePt, EPSILON)
        assertEquals(21.6, line.heightPt, EPSILON)
    }

    @Test
    fun `shape autofit keeps the stored size`() {
        val line = layout(TextAutofit.Shape).lines.single()

        assertEquals(18.0, line.runs.single().sizePt, EPSILON)
    }

    @Test
    fun `the line spacing reduction cuts a percent spacing`() {
        val line = layout(
            TextAutofit.Normal(fontScale = 100.0, lineSpacingReduction = 20.0),
            lineSpacing = TextSpacing(percent = 100.0),
        ).lines.single()

        assertEquals(17.28, line.heightPt, EPSILON)
    }

    @Test
    fun `the line spacing reduction leaves a point spacing alone`() {
        val line = layout(
            TextAutofit.Normal(fontScale = 100.0, lineSpacingReduction = 20.0),
            lineSpacing = TextSpacing(points = 24.0),
        ).lines.single()

        assertEquals(24.0, line.heightPt, EPSILON)
    }

    @Test
    fun `a percent paragraph spacing follows the scaled size`() {
        val layout = layout(
            autofit = TextAutofit.Normal(fontScale = 50.0, lineSpacingReduction = 0.0),
            spaceAfter = TextSpacing(percent = 100.0),
        )

        assertEquals(21.6, layout.heightPt, EPSILON)
    }

    @Test
    fun `a bullet inherits the scaled run size`() {
        val line = layout(
            TextAutofit.Normal(fontScale = 50.0, lineSpacingReduction = 0.0),
            bullet = TextBullet.Character("•", font = null, color = null, size = TextBulletSize.Inherit),
        ).lines.single()

        assertEquals(9.0, (line.bullet as BulletMarker.Symbol).sizePt, EPSILON)
    }

    private fun layout(
        autofit: TextAutofit,
        sizePt: Double = 18.0,
        lineSpacing: TextSpacing? = null,
        spaceAfter: TextSpacing? = null,
        bullet: TextBullet = TextBullet.None,
    ): TextFrameLayout {
        val style = TextRunProperties(sizePt = sizePt, latinTypeface = "Calibri")
        return engine.layout(
            TextBody(
                properties = TextBodyProperties(insets = NO_INSETS, autofit = autofit),
                paragraphs = listOf(
                    TextParagraph(
                        properties = TextParagraphProperties(
                            lineSpacing = lineSpacing,
                            spaceAfter = spaceAfter,
                            bullet = bullet,
                        ),
                        runs = listOf(TextRun.Text("aa", style)),
                        endProperties = style,
                    ),
                ),
            ),
            WIDTH_PT * TextLayoutEngine.EMU_PER_POINT,
        )
    }

    private fun wordBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it - 1] == ' ' }

    private companion object {

        const val EPSILON = 1e-6

        const val WIDTH_PT = 200.0

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)
    }
}
