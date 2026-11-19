package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletResolverTest {

    private val resolver = BulletResolver()

    private val red = Rgba(0xFF, 0x00, 0x00)

    @Test
    fun `buNone has no marker`() {
        assertTrue(resolver.marker(TextBullet.None, 18.0, red) is BulletMarker.None)
    }

    @Test
    fun `a char bullet keeps its character and declared face`() {
        val marker = character("•", font = "Arial") as BulletMarker.Symbol

        assertEquals("•", marker.text)
        assertEquals("Arial", marker.font)
        assertNull(marker.shape)
    }

    @Test
    fun `a symbol-font bullet takes its encoded Unicode glyph and the platform chain`() {
        val marker = character("l", font = "Wingdings") as BulletMarker.Symbol

        assertEquals("\u26AB", marker.text)
        assertNull(marker.font)
    }

    @Test
    fun `a symbol-font bullet without a usable glyph takes vector geometry`() {
        val marker = character("m", font = "Wingdings") as BulletMarker.Symbol

        assertNull(marker.text)
        assertTrue(marker.shape?.paths?.isNotEmpty() == true)
    }

    @Test
    fun `a char that is not in the symbol table falls back to the stored character`() {
        val marker = character("A", font = "Wingdings") as BulletMarker.Symbol

        assertEquals("A", marker.text)
        assertEquals("Wingdings", marker.font)
    }

    @Test
    fun `an empty char has no marker text`() {
        assertNull(character("").text)
    }

    @Test
    fun `buSzPct scales the run's size`() {
        assertEquals(10.8, character("•", size = TextBulletSize.Percent(60.0)).sizePt, 0.0)
    }

    @Test
    fun `buSzPts sets the marker's size in points`() {
        assertEquals(9.0, character("•", size = TextBulletSize.Points(9.0)).sizePt, 0.0)
    }

    @Test
    fun `buSzTx keeps the run's size`() {
        assertEquals(20.0, character("•", size = TextBulletSize.Inherit, baseSize = 20.0).sizePt, 0.0)
    }

    @Test
    fun `buClr outranks the run's colour`() {
        assertEquals(red, character("•", color = red).color)
    }

    @Test
    fun `a bullet without buClr takes the run's colour`() {
        assertEquals(red, character("•", baseColor = red).color)
    }

    @Test
    fun `buBlip carries its image, size and colour`() {
        val marker = resolver.marker(
            TextBullet.Picture(image = null, color = red, size = TextBulletSize.Percent(50.0)),
            18.0,
            null,
        ) as BulletMarker.Picture

        assertEquals(red, marker.color)
        assertEquals(9.0, marker.sizePt, 0.0)
    }

    @Test
    fun `levels one to nine step the default text indent`() {
        for (level in 0..8) {
            val metrics = resolver.metrics(level, TextParagraphProperties())

            assertEquals(level * 457_200.0, metrics.textLeftEmu, 0.0)
            assertEquals(metrics.textLeftEmu, metrics.markerLeftEmu, 0.0)
            assertEquals(0.0, metrics.hangingEmu, 0.0)
        }
    }

    @Test
    fun `a negative indent hangs the marker left of the text`() {
        val metrics = resolver.metrics(
            0,
            TextParagraphProperties(marginLeftEmu = 342_900.0, indentEmu = -285_750.0),
        )

        assertEquals(342_900.0, metrics.textLeftEmu, 0.0)
        assertEquals(57_150.0, metrics.markerLeftEmu, 0.0)
        assertEquals(285_750.0, metrics.hangingEmu, 0.0)
    }

    @Test
    fun `a positive indent leaves the marker at the margin`() {
        val metrics = resolver.metrics(1, TextParagraphProperties(marginLeftEmu = 457_200.0, indentEmu = 91_440.0))

        assertEquals(457_200.0, metrics.markerLeftEmu, 0.0)
        assertEquals(0.0, metrics.hangingEmu, 0.0)
    }

    @Test
    fun `a paragraph's own margin and indent outrank the level defaults`() {
        val metrics = resolver.metrics(
            4,
            TextParagraphProperties(marginLeftEmu = 1_000_000.0, indentEmu = -200_000.0),
        )

        assertEquals(1_000_000.0, metrics.textLeftEmu, 0.0)
        assertEquals(800_000.0, metrics.markerLeftEmu, 0.0)
    }

    @Test
    fun `the base size and colour come from the first run`() {
        val layout = resolver.layout(
            TextParagraph(
                runs = listOf(
                    TextRun.Text("x", TextRunProperties(sizePt = 24.0, fill = ScenePaint.Solid(red))),
                ),
                properties = TextParagraphProperties(bullet = TextBullet.Character("•", null, null, TextBulletSize.Inherit)),
            ),
        )

        assertEquals(24.0, (layout.marker as BulletMarker.Symbol).sizePt, 0.0)
        assertEquals(red, layout.marker.color)
    }

    @Test
    fun `an empty paragraph takes its base size from the paragraph mark`() {
        val layout = resolver.layout(
            TextParagraph(
                properties = TextParagraphProperties(bullet = TextBullet.Character("•", null, null, TextBulletSize.Inherit)),
                endProperties = TextRunProperties(sizePt = 14.0, fill = ScenePaint.Solid(red)),
            ),
        )

        assertEquals(14.0, (layout.marker as BulletMarker.Symbol).sizePt, 0.0)
    }

    private fun character(
        char: String,
        font: String? = null,
        color: Rgba? = null,
        size: TextBulletSize = TextBulletSize.Inherit,
        baseSize: Double = 18.0,
        baseColor: Rgba? = null,
    ): BulletMarker.Symbol = resolver.marker(
        TextBullet.Character(char = char, font = font, color = color, size = size),
        baseSize,
        baseColor,
    ) as BulletMarker.Symbol
}
