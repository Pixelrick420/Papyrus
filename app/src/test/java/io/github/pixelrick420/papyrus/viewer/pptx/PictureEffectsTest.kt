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

    private fun effects(xml: String): PictureEffects = PictureEffects.of(
        OoxmlReader(OfficeFixtures::parser).readTree(
            """<a:blip xmlns:a="${OoxmlNamespace.A}">$xml</a:blip>""".byteInputStream(),
        ),
    )
}
