package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureEffectsTest {

    @Test
    fun `a plain blip has no recolour effects`() {
        assertTrue(effects("").isEmpty)
    }

    @Test
    fun `grayscale maps each channel to its luma`() {
        val effect = effects("<a:grayscl/>")
        val red = effect.applyTo(Rgba(255, 0, 0))
        val green = effect.applyTo(Rgba(0, 255, 0))

        assertEquals(Rgba(76, 76, 76), red)
        assertEquals(Rgba(150, 150, 150), green)
        assertEquals(255, red.alpha)
    }

    @Test
    fun `brightness lifts black and leaves white at white`() {
        val effect = effects("""<a:lum bright="25000"/>""")

        val black = effect.applyTo(Rgba(0, 0, 0))
        val red = effect.applyTo(Rgba(255, 0, 0))

        assertEquals(64, black.red)
        assertEquals(255, red.red)
        assertEquals(64, red.green)
        assertEquals(64, red.blue)
    }

    @Test
    fun `contrast flattens every colour to middle grey`() {
        val out = effects("""<a:lum contrast="-100000"/>""").applyTo(Rgba(255, 0, 0))

        assertEquals(128, out.red)
        assertEquals(128, out.green)
        assertEquals(128, out.blue)
    }

    @Test
    fun `biLevel splits pixels at the threshold`() {
        val effect = effects("""<a:biLevel thresh="50000"/>""")

        assertEquals(Rgba.BLACK, effect.applyTo(Rgba(10, 10, 10)))
        assertEquals(Rgba.WHITE, effect.applyTo(Rgba(240, 240, 240)))
        assertEquals(Rgba.WHITE, effect.applyTo(Rgba(128, 128, 128)))
        assertEquals(Rgba.BLACK, effect.applyTo(Rgba(127, 127, 127)))
    }

    @Test
    fun `effects combine in document order`() {
        val effect = effects("<a:grayscl/><a:lum contrast=\"-100000\"/>")

        val out = effect.applyTo(Rgba(255, 0, 0))

        assertFalse(effect.isEmpty)
        assertEquals(128, out.red)
        assertEquals(128, out.green)
        assertEquals(128, out.blue)
    }

    @Test
    fun `a malformed value contributes nothing`() {
        assertTrue(effects("""<a:lum bright="nope" contrast="also-nope"/>""").isEmpty)
    }

    @Test
    fun `duotone maps luminance between its two colours`() {
        val effect = effects(
            """<a:duotone><a:srgbClr val="FF0000"/><a:srgbClr val="0000FF"/></a:duotone>""",
        )

        assertTrue(effect.hasPixelWork)
        assertEquals(Rgba(255, 0, 0), effect.applyTo(Rgba.BLACK))
        assertEquals(Rgba(0, 0, 255), effect.applyTo(Rgba.WHITE))
        val mid = effect.applyTo(Rgba(128, 128, 128))
        assertEquals(128.0, mid.red.toDouble(), 2.0)
        assertEquals(0.0, mid.green.toDouble(), 2.0)
        assertEquals(127.0, mid.blue.toDouble(), 2.0)
    }

    @Test
    fun `clrChange replaces near matches and keeps other pixels`() {
        val effect = effects(
            """<a:clrChange><a:clrFrom><a:srgbClr val="FF0000"/></a:clrFrom>""" +
                """<a:clrTo><a:srgbClr val="00FF00"/></a:clrTo></a:clrChange>""",
        )

        assertEquals(Rgba(0, 255, 0), effect.applyTo(Rgba(255, 0, 0)))
        assertEquals(Rgba(0, 255, 0), effect.applyTo(Rgba(250, 5, 5)))
        assertEquals(Rgba(0, 0, 255), effect.applyTo(Rgba(0, 0, 255)))
    }

    @Test
    fun `tint blends toward its colour by its amount`() {
        val effect = effects("""<a:tint amt="50000"><a:srgbClr val="FF0000"/></a:tint>""")

        assertEquals(Rgba(128, 0, 0), effect.applyTo(Rgba.BLACK))
        assertEquals(Rgba(255, 128, 128), effect.applyTo(Rgba.WHITE))
    }

    @Test
    fun `hsl shifts hue saturation and luminance`() {
        val hue = effects("""<a:hsl hue="3600000"/>""")
        val saturation = effects("""<a:hsl sat="-100000"/>""")
        val luminance = effects("""<a:hsl lum="-25000"/>""")

        assertEquals(Rgba(255, 255, 0), hue.applyTo(Rgba(255, 0, 0)))
        assertEquals(Rgba(128, 128, 128), saturation.applyTo(Rgba(255, 0, 0)))
        assertEquals(Rgba(128, 0, 0), luminance.applyTo(Rgba(255, 0, 0)))
    }

    @Test
    fun `matrix and pixel effects apply in sequence`() {
        val effect = effects("<a:grayscl/><a:tint amt=\"50000\"><a:srgbClr val=\"FF0000\"/></a:tint>")

        assertEquals(Rgba(166, 38, 38), effect.applyTo(Rgba(255, 0, 0)))
    }

    private fun effects(xml: String): PictureEffects = PictureEffects.of(
        OoxmlReader(OfficeFixtures::parser).readTree(
            """<a:blip xmlns:a="${OoxmlNamespace.A}">$xml</a:blip>""".byteInputStream(),
        ),
    )
}
