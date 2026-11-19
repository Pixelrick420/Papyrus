package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class TextWarpRendererTest {

    private val renderer = TextWarpRenderer(
        PresetGeometry(
            open = { File("src/main/assets/ooxml/presetTextWarpDefinitions.xml").inputStream() },
            newParserFactory = { KXmlParser() },
        ),
    )

    @Test
    fun `a known preset bends each character along its path`() {
        val properties = properties(TextWarp("textArchUp"))
        val unwarped = layout(properties)

        val warped = renderer.warp(unwarped.lines, properties, FRAME_WIDTH_EMU, FRAME_HEIGHT_EMU)

        assertNotSame(unwarped.lines, warped)
        val line = warped.single()
        assertEquals(listOf("a", "b", "c"), line.runs.map { it.text })
        assertTrue(line.runs.any { it.rotationDeg != 0.0 })
        val centers = line.characters.map { (it.topPt + it.bottomPt) / 2.0 }
        assertTrue(centers.zipWithNext().any { (first, second) -> first != second })
    }

    @Test
    fun `explicit adjust values change the warp`() {
        val plain = bend(TextWarp("textArchUp"))
        val adjusted = bend(TextWarp("textArchUp", mapOf("adj" to "val 1000")))

        assertFalse(plain == adjusted)
    }

    @Test
    fun `an unknown preset leaves the text unchanged`() {
        val properties = properties(TextWarp("doesNotExist"))
        val unwarped = layout(properties)

        assertSame(unwarped.lines, renderer.warp(unwarped.lines, properties, FRAME_WIDTH_EMU, FRAME_HEIGHT_EMU))
    }

    @Test
    fun `the layout engine applies a warp when one is configured`() {
        val properties = properties(TextWarp("textArchUp"))
        val engine = TextLayoutEngine(
            measurer = BlockMeasurer(),
            breaker = LineBreaker { text, _ -> wordBreaks(text) },
            warps = renderer,
        )

        val warped = engine.layout(body(properties), FRAME_WIDTH_EMU, FRAME_HEIGHT_EMU)

        assertTrue(warped.lines.single().runs.any { it.rotationDeg != 0.0 })
    }

    private fun bend(warp: TextWarp): List<Pair<Double, Double>> {
        val properties = properties(warp)
        return renderer.warp(layout(properties).lines, properties, FRAME_WIDTH_EMU, FRAME_HEIGHT_EMU)
            .flatMap { line ->
                line.characters.map { box ->
                    (box.topPt + box.bottomPt) / 2.0 to (box.leftPt + box.rightPt) / 2.0
                }
            }
    }

    private fun properties(warp: TextWarp): TextBodyProperties =
        TextBodyProperties(insets = NO_INSETS, warp = warp)

    private fun layout(properties: TextBodyProperties): TextFrameLayout =
        TextLayoutEngine(
            measurer = BlockMeasurer(),
            breaker = LineBreaker { text, _ -> wordBreaks(text) },
        ).layout(body(properties), FRAME_WIDTH_EMU, FRAME_HEIGHT_EMU)

    private fun body(properties: TextBodyProperties): TextBody = TextBody(
        properties = properties,
        paragraphs = listOf(
            TextParagraph(
                runs = listOf(TextRun.Text("abc", STYLE)),
                endProperties = STYLE,
            ),
        ),
    )

    private fun wordBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it - 1] == ' ' }

    private companion object {
        const val FRAME_WIDTH_PT = 200.0
        const val FRAME_HEIGHT_PT = 100.0
        const val FRAME_WIDTH_EMU = FRAME_WIDTH_PT * TextLayoutEngine.EMU_PER_POINT
        const val FRAME_HEIGHT_EMU = FRAME_HEIGHT_PT * TextLayoutEngine.EMU_PER_POINT

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = 18.0, latinTypeface = "Calibri")
    }
}
