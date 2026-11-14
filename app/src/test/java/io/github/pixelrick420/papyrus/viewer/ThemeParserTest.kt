package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxFormatException
import io.github.pixelrick420.papyrus.viewer.pptx.Theme
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeParserTest {

    private fun parse(xml: String): Theme = ThemeParser(OfficeFixtures::parser).parse(xml.trimIndent().toByteArray(), "theme1.xml")

    private fun Deck.theme(): Theme = parse(parts.getValue("ppt/theme/theme1.xml").toString(Charsets.UTF_8))

    private fun colourOf(theme: Theme, slot: String): String? =
        theme.colors[slot]?.let { ColorModel(theme.colors).resolve(it) }?.let {
            "%02X%02X%02X".format(it.red, it.green, it.blue)
        }

    @Test
    fun `a fixture deck's theme reads its colours, fonts and format lists`() {
        val theme = pptx {
            theme { accent1("4472C4"); majorFont("Calibri Light"); minorFont("Calibri") }
        }.theme()

        assertEquals("Office Theme", theme.name)
        assertEquals(12, theme.colors.names.size)
        assertEquals("4472C4", colourOf(theme, "accent1"))
        assertEquals("FFFFFF", colourOf(theme, "lt1"))
        assertEquals("Calibri Light", theme.fonts.major.latin)
        assertEquals("Calibri", theme.fonts.minor.latin)
        assertEquals("Calibri Light", theme.fonts.resolve("+mj-lt"))
        assertEquals("Calibri", theme.fonts.resolve("+mn-lt"))
        assertEquals(3, theme.formats.fills.size)
        assertEquals(3, theme.formats.lines.size)
        assertEquals(3, theme.formats.effects.size)
        assertEquals(3, theme.formats.backgroundFills.size)
        assertNotNull(theme.formats.fill(1))
        assertNotNull(theme.formats.line(3))
        assertNotNull(theme.formats.effect(2))
        assertNotNull(theme.formats.background(1001))
        assertNull(theme.formats.fill(4))
        assertNull(theme.formats.background(1000))
    }

    @Test
    fun `a theme reads system and HSL colours, per-script fonts and blank faces`() {
        val theme = parse(rawTheme())

        assertEquals("000000", colourOf(theme, "dk1"))
        assertEquals("FFFFFF", colourOf(theme, "lt1"))
        assertEquals("008000", colourOf(theme, "accent2"))
        assertEquals("Yu Gothic", theme.fonts.major.eastAsian)
        assertEquals("Arial", theme.fonts.major.complexScript)
        assertEquals("游ゴシック", theme.fonts.major.scripts["Jpan"])
        assertEquals("游ゴシック", theme.fonts.resolve("+mj-Jpan"))
        assertEquals("Yu Gothic", theme.fonts.resolve("+mj-ea"))
        assertEquals("Arial", theme.fonts.resolve("+mj-cs"))
        assertEquals("Arial", theme.fonts.resolve("+mn-Arab"))
        assertNull(theme.fonts.resolve("+mn-ea"))
        assertNull(theme.fonts.resolve("mj-lt"))
        assertNull(theme.fonts.resolve("+xx-lt"))
        assertNull(theme.fonts.resolve("+mj"))
    }

    @Test
    fun `objectDefaults are not read as format-scheme entries`() {
        val theme = parse(rawTheme())

        assertEquals(3, theme.formats.fills.size)
        assertEquals("solidFill", theme.formats.fill(1)?.name?.localName)
    }

    @Test
    fun `a strict theme reads the same as its transitional twin`() {
        val strict = rawTheme()
            .replace("xmlns:a=\"${OoxmlNamespace.A}\"", "xmlns:a=\"http://purl.oclc.org/ooxml/drawingml/main\"")

        val theme = parse(strict)

        assertEquals("008000", colourOf(theme, "accent2"))
        assertEquals("游ゴシック", theme.fonts.resolve("+mj-Jpan"))
    }

    @Test
    fun `a part that is not a theme is refused`() {
        val failure = runCatching {
            parse("""<a:themeElements xmlns:a="${OoxmlNamespace.A}"/>""")
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
    }

    @Test
    fun `a theme with no themeElements is refused`() {
        val failure = runCatching {
            parse("""<a:theme xmlns:a="${OoxmlNamespace.A}" name="Empty"/>""")
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
    }

    @Test
    fun `an empty part is refused`() {
        val failure = runCatching {
            ThemeParser(OfficeFixtures::parser).parse(ByteArray(0), "theme1.xml")
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
    }

    @Test
    fun `a theme with no schemes still yields empty lists`() {
        val theme = parse("""<a:theme xmlns:a="${OoxmlNamespace.A}"><a:themeElements/></a:theme>""")

        assertTrue(theme.colors.names.isEmpty())
        assertTrue(theme.formats.fills.isEmpty())
        assertNull(theme.fonts.major.latin)
        assertNull(theme.fonts.resolve("+mj-lt"))
    }

    private fun rawTheme(): String {
        val slots = listOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6")
        val scheme = slots.joinToString("") { slot ->
            val body = when (slot) {
                "dk1" -> """<a:sysClr val="windowText" lastClr="000000"/>"""
                "lt1" -> """<a:sysClr val="window" lastClr="FFFFFF"/>"""
                "accent2" -> """<a:hslClr hue="7200000" sat="100000" lum="25000"/>"""
                else -> """<a:srgbClr val="44546A"/>"""
            }
            "<a:$slot>$body</a:$slot>"
        } + """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink>"""

        return """
            <a:theme xmlns:a="${OoxmlNamespace.A}" name="Custom">
                <a:themeElements>
                    <a:clrScheme name="Custom">$scheme</a:clrScheme>
                    <a:fontScheme name="Custom">
                        <a:majorFont>
                            <a:latin typeface="Calibri Light"/>
                            <a:ea typeface="Yu Gothic"/>
                            <a:cs typeface="Arial"/>
                            <a:font script="Jpan" typeface="游ゴシック"/>
                            <a:font script="Hang" typeface="맑은 고딕"/>
                        </a:majorFont>
                        <a:minorFont>
                            <a:latin typeface="Calibri"/>
                            <a:ea typeface=""/>
                            <a:cs typeface=""/>
                            <a:font script="Arab" typeface="Arial"/>
                        </a:minorFont>
                    </a:fontScheme>
                    <a:fmtScheme name="Custom">
                        <a:fillStyleLst>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"><a:tint val="50000"/></a:schemeClr></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill>
                        </a:fillStyleLst>
                        <a:lnStyleLst>
                            <a:ln w="6350"/><a:ln w="12700"/><a:ln w="19050"/>
                        </a:lnStyleLst>
                        <a:effectStyleLst>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                        </a:effectStyleLst>
                        <a:bgFillStyleLst>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                        </a:bgFillStyleLst>
                    </a:fmtScheme>
                </a:themeElements>
                <a:objectDefaults>
                    <a:spDef><a:style><a:lnRef idx="1"/><a:fillRef idx="1"/></a:style></a:spDef>
                </a:objectDefaults>
            </a:theme>
        """
    }
}
