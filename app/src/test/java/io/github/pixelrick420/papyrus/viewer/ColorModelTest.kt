package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.ColorMap
import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.ColorSource
import io.github.pixelrick420.papyrus.viewer.pptx.ColorValue
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeColorScheme
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorModelTest {

    private fun tree(xml: String): XNode =
        OoxmlReader(OfficeFixtures::parser).readTree(xml.byteInputStream(), "test.xml")!!

    private fun colour(name: String, attributes: String, children: String = ""): XNode =
        tree("""<a:$name xmlns:a="${OoxmlNamespace.A}" $attributes>$children</a:$name>""")

    private fun srgb(hex: String, children: String = ""): XNode = colour("srgbClr", """val="$hex"""", children)

    private fun resolve(node: XNode, model: ColorModel = ColorModel(), placeholder: Rgba? = null): Rgba? =
        model.resolve(node, placeholder)

    private fun hex(node: XNode, model: ColorModel = ColorModel(), placeholder: Rgba? = null): String {
        val resolved = resolve(node, model, placeholder) ?: error("the colour did not resolve")
        return "%02X%02X%02X".format(resolved.red, resolved.green, resolved.blue)
    }

    private fun scheme(vararg slots: Pair<String, String>): ThemeColorScheme = ThemeColorScheme(
        slots.associate { (slot, value) -> slot to ColorValue(ColorSource.Srgb(Rgba.of(value.toInt(16)))) },
    )

    @Test
    fun `a colour is read from six or eight hex digits`() {
        assertEquals(Rgba(0x12, 0x34, 0x56, 255), Rgba.parse("123456"))
        assertEquals(Rgba(0x12, 0x34, 0x56, 0x78), Rgba.parse("78123456"))
        assertNull(Rgba.parse("12345"))
        assertNull(Rgba.parse("GGGGGG"))
    }

    @Test
    fun `a colour packs as ARGB`() {
        assertEquals(0x804472C4.toInt(), Rgba(0x44, 0x72, 0xC4, 0x80).argb)
    }

    @Test
    fun `an sRGB colour resolves to itself`() {
        assertEquals("4472C4", hex(srgb("4472C4")))
    }

    @Test
    fun `a colour written with alpha keeps it`() {
        assertEquals(Rgba(255, 0, 0, 0x80), resolve(srgb("80FF0000")))
    }

    @Test
    fun `a preset colour is looked up by name whatever its case`() {
        assertEquals("FF0000", hex(colour("prstClr", """val="red"""")))
        assertEquals("00008B", hex(colour("prstClr", """val="DarkBlue"""")))
        assertNull(resolve(colour("prstClr", """val="not-a-colour"""")))
    }

    @Test
    fun `a system colour uses the last value PowerPoint wrote`() {
        assertEquals("FFEEDD", hex(colour("sysClr", """val="windowText" lastClr="FFEEDD"""")))
        assertNull(resolve(colour("sysClr", """val="windowText"""")))
    }

    @Test
    fun `an scRGB colour is given as linear light`() {
        assertEquals("BCBCBC", hex(colour("scrgbClr", """r="50000" g="50000" b="50000"""")))
    }

    @Test
    fun `an HSL colour is given in hundred-thousandths`() {
        assertEquals("FF0000", hex(colour("hslClr", """hue="0" sat="100000" lum="50000"""")))
        assertEquals("008000", hex(colour("hslClr", """hue="7200000" sat="100000" lum="25000"""")))
    }

    @Test
    fun `a scheme colour is looked up through the colour map`() {
        val model = ColorModel(scheme("dk1" to "111111", "lt1" to "FFFFFF", "accent1" to "4472C4"))

        assertEquals("111111", hex(colour("schemeClr", """val="tx1""""), model))
        assertEquals("FFFFFF", hex(colour("schemeClr", """val="bg1""""), model))
        assertEquals("4472C4", hex(colour("schemeClr", """val="accent1""""), model))
    }

    @Test
    fun `a colour map override re-points a name before it is looked up`() {
        val model = ColorModel(
            scheme("dk2" to "222222"),
            ColorMap.from(tree("""<p:clrMap xmlns:p="${OoxmlNamespace.P}" bg1="dk2"/>""")),
        )

        assertEquals("222222", hex(colour("schemeClr", """val="bg1""""), model))
    }

    @Test
    fun `a scheme colour with no theme or an unknown slot resolves to nothing`() {
        assertNull(resolve(colour("schemeClr", """val="accent1"""")))
        assertNull(resolve(colour("schemeClr", """val="accent9""""), ColorModel(scheme("accent1" to "4472C4"))))
    }

    @Test
    fun `a placeholder colour is replaced by the shape's colour`() {
        val node = colour("schemeClr", """val="phClr"""", """<a:tint val="50000"/>""")

        assertEquals("CDCDCD", hex(node, placeholder = Rgba.of(0x808080)))
        assertNull(resolve(node))
    }

    @Test
    fun `tint and shade move a colour towards white and black in linear light`() {
        assertEquals("CDCDCD", hex(srgb("808080", """<a:tint val="50000"/>""")))
        assertEquals("5C5C5C", hex(srgb("808080", """<a:shade val="50000"/>""")))
        assertEquals("E3E6F2", hex(srgb("4472C4", """<a:tint val="25000"/>""")))
    }

    @Test
    fun `luminance transforms apply in document order`() {
        assertEquals("A2B8E1", hex(srgb("4472C4", """<a:lumMod val="50000"/><a:lumOff val="50000"/>""")))
        assertEquals("808080", hex(srgb("4472C4", """<a:lumOff val="50000"/><a:lumMod val="50000"/>""")))
    }

    @Test
    fun `gamma crosses into linear light and invGamma crosses back`() {
        assertEquals("373737", hex(srgb("808080", """<a:gamma/>""")))
        assertEquals("808080", hex(srgb("808080", """<a:gamma/><a:invGamma/>""")))
        assertEquals("BFBFBF", hex(srgb("808080", """<a:gamma/><a:tint val="50000"/>""")))
    }

    @Test
    fun `inversion and complement are taken in sRGB and HSL`() {
        assertEquals("BB8D3B", hex(srgb("4472C4", """<a:inv/>""")))
        assertEquals("C49644", hex(srgb("4472C4", """<a:comp/>""")))
    }

    @Test
    fun `gray removes saturation and leaves luminance`() {
        assertEquals("848484", hex(srgb("4472C4", """<a:gray/>""")))
    }

    @Test
    fun `saturation and hue transforms work in HSL`() {
        assertEquals("647BA4", hex(srgb("4472C4", """<a:satMod val="50000"/>""")))
        assertEquals("55FF00", hex(srgb("FF0000", """<a:hueOff val="6000000"/>""")))
    }

    @Test
    fun `a per-channel transform touches only its own channel`() {
        assertEquals("800000", hex(srgb("000000", """<a:red val="50000"/>""")))
        assertEquals("008000", hex(srgb("00FF00", """<a:greenMod val="50000"/>""")))
    }

    @Test
    fun `alpha transforms set the alpha channel without touching the colour`() {
        val resolved = resolve(srgb("4472C4", """<a:alpha val="40000"/>"""))!!

        assertEquals("4472C4", "%02X%02X%02X".format(resolved.red, resolved.green, resolved.blue))
        assertEquals(102, resolved.alpha)
    }

    @Test
    fun `an unknown transform or a missing source resolves to nothing`() {
        assertNull(resolve(srgb("not-hex")))
        assertNull(resolve(tree("""<a:solidFill xmlns:a="${OoxmlNamespace.A}"/>""")))
    }

    @Test
    fun `an unknown transform is skipped`() {
        assertEquals("4472C4", hex(srgb("4472C4", """<a:notATransform val="1"/><a:unknownThing/>""")))
    }
}
