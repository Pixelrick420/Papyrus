package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {

    private val colors = ColorModel()

    @Test
    fun `an outer shadow keeps its geometry colour and alpha`() {
        val effects = effects(
            """<a:outerShdw blurRad="63500" dist="127000" dir="2700000" sx="110000" sy="90000" """ +
                """kx="60000" ky="-60000" algn="tl" rotWithShape="0">""" +
                """<a:srgbClr val="336699"><a:alpha val="50000"/></a:srgbClr></a:outerShdw>""",
        )

        val shadow = effects!!.items.single() as SceneEffect.Shadow
        assertEquals(63_500.0, shadow.blurEmu, 0.0)
        assertEquals(127_000.0, shadow.distanceEmu, 0.0)
        assertEquals(45.0, shadow.directionDegrees, 0.0)
        assertEquals(1.1, shadow.scaleX, 0.0)
        assertEquals(0.9, shadow.scaleY, 0.0)
        assertEquals(1.0, shadow.skewX, 0.0)
        assertEquals(-1.0, shadow.skewY, 0.0)
        assertEquals("tl", shadow.align)
        assertFalse(shadow.rotateWithShape)
        assertFalse(shadow.inner)
        assertEquals(0x33, shadow.color.red)
        assertEquals(0x66, shadow.color.green)
        assertEquals(0x99, shadow.color.blue)
        assertTrue(shadow.color.alpha in 127..128)
    }

    @Test
    fun `an inner shadow is marked as inner`() {
        val effects = effects("""<a:innerShdw blurRad="12700"><a:srgbClr val="FF0000"/></a:innerShdw>""")

        val shadow = effects!!.items.single() as SceneEffect.Shadow
        assertTrue(shadow.inner)
        assertEquals(12_700.0, shadow.blurEmu, 0.0)
        assertEquals(Rgba(255, 0, 0), shadow.color)
    }

    @Test
    fun `a preset shadow fills in its distance direction and blur`() {
        val effects = effects("""<a:prstShdw prst="shdw3"><a:schemeClr val="accent1"/></a:prstShdw>""")

        val shadow = effects!!.items.single() as SceneEffect.Shadow
        assertTrue(shadow.preset)
        assertEquals(4 * 12_700.0, shadow.distanceEmu, 0.0)
        assertEquals(135.0, shadow.directionDegrees, 0.0)
        assertEquals(0.0, shadow.blurEmu, 0.0)
    }

    @Test
    fun `explicit preset attributes override the preset`() {
        val effects = effects("""<a:prstShdw prst="shdw3" dist="25400" dir="1800000" blurRad="6350"/>""")

        val shadow = effects!!.items.single() as SceneEffect.Shadow
        assertEquals(25_400.0, shadow.distanceEmu, 0.0)
        assertEquals(30.0, shadow.directionDegrees, 0.0)
        assertEquals(6_350.0, shadow.blurEmu, 0.0)
    }

    @Test
    fun `glow soft edge and blur are resolved`() {
        val effects = effects(
            """<a:glow rad="6350"><a:srgbClr val="00FF00"/></a:glow>""" +
                """<a:softEdge rad="12700"/>""" +
                """<a:blur/>""",
        )

        val glow = effects!!.items[0] as SceneEffect.Glow
        val softEdge = effects.items[1] as SceneEffect.SoftEdge
        assertTrue(effects.items[2] is SceneEffect.Blur)
        assertEquals(Rgba(0, 255, 0), glow.color)
        assertEquals(6_350.0, glow.radiusEmu, 0.0)
        assertEquals(12_700.0, softEdge.radiusEmu, 0.0)
    }

    @Test
    fun `an empty effect list resolves to an empty model`() {
        assertNotNull(effects(""))
        assertTrue(effects("")!!.items.isEmpty())
    }

    private fun effects(xml: String): SceneEffects? =
        Effects.of(
            OoxmlReader(OfficeFixtures::parser)
                .readTree("""<a:effectLst xmlns:a="${OoxmlNamespace.A}">$xml</a:effectLst>""".byteInputStream()),
            colors,
        )
}
