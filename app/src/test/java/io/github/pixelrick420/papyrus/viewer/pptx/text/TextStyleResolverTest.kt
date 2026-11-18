package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.ColorSource
import io.github.pixelrick420.papyrus.viewer.pptx.ColorValue
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.Theme
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeColorScheme
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeFontScheme
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeFonts
import io.github.pixelrick420.papyrus.viewer.pptx.ThemeFormatScheme
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleResolverTest {

    private val resolver = TextStyleResolver()

    private fun node(xml: String): XNode =
        OoxmlReader(OfficeFixtures::parser).readTree(xml.toByteArray().inputStream(), "test.xml")!!

    private fun body(xml: String): XNode = node("""<a:txBody $NAMESPACES>$xml</a:txBody>""")

    private fun style(tag: String, xml: String): XNode = node("""<$tag $NAMESPACES>$xml</$tag>""")

    private fun level(properties: String, level: Int = 1): XNode = style(
        "a:lstStyle",
        """<a:lvl${level}pPr>$properties</a:lvl${level}pPr>""",
    )

    private fun theme(
        minor: String? = "Calibri",
        major: String? = "Calibri Light",
        dk1: Rgba? = Rgba.BLACK,
        hlink: Rgba? = Rgba(0x05, 0x63, 0xC1),
        folHlink: Rgba? = Rgba(0x95, 0x4F, 0x72),
    ): Pair<Theme, ColorModel> {
        val slots = mapOf("dk1" to dk1, "hlink" to hlink, "folHlink" to folHlink)
            .mapNotNull { (slot, color) -> color?.let { slot to ColorValue(ColorSource.Srgb(it)) } }
            .toMap()
        val scheme = ThemeColorScheme(slots)
        val fonts = ThemeFontScheme(
            major = ThemeFonts(major, null, null, emptyMap()),
            minor = ThemeFonts(minor, null, null, emptyMap()),
        )
        return Theme("Test", scheme, fonts, ThemeFormatScheme(emptyList(), emptyList(), emptyList(), emptyList())) to
            ColorModel(scheme)
    }

    private fun sizeOf(sources: TextStyleSources, paragraph: String = "<a:p><a:r><a:t>x</a:t></a:r></a:p>"): Double =
        resolver.resolve(body(paragraph), sources).paragraphs.single().runs.single().properties.sizePt

    @Test
    fun `a run's own rPr outranks every inherited level`() {
        val sources = TextStyleSources(
            listStyles = listOf(level("""<a:defRPr sz="900"/>""")),
            masterStyle = style("p:bodyStyle", """<a:lvl1pPr><a:defRPr sz="1000"/></a:lvl1pPr>"""),
            defaultTextStyle = style("p:defaultTextStyle", """<a:lvl1pPr><a:defRPr sz="1100"/></a:lvl1pPr>"""),
        )

        val run = resolver.resolve(
            body("""<a:p><a:r><a:rPr sz="2400" b="1"/><a:t>x</a:t></a:r></a:p>"""),
            sources,
        ).paragraphs.single().runs.single()

        assertEquals(24.0, run.properties.sizePt, 0.0)
        assertTrue(run.properties.bold)
    }

    @Test
    fun `a paragraph defRPr outranks the lstStyle levels`() {
        val sources = TextStyleSources(listStyles = listOf(level("""<a:defRPr sz="900"/>""")))

        assertEquals(20.0, sizeOf(sources, """<a:p><a:pPr><a:defRPr sz="2000"/></a:pPr><a:r><a:t>x</a:t></a:r></a:p>"""), 0.0)
    }

    @Test
    fun `the shape's lstStyle outranks the layout's and the master's`() {
        val sources = TextStyleSources(
            listStyles = listOf(
                level("""<a:defRPr sz="1400"/>"""),
                level("""<a:defRPr sz="1300"/>"""),
                level("""<a:defRPr sz="1200"/>"""),
            ),
        )

        assertEquals(14.0, sizeOf(sources), 0.0)
    }

    @Test
    fun `the master's txStyles entry outranks the presentation default`() {
        val sources = TextStyleSources(
            masterStyle = style("p:titleStyle", """<a:lvl1pPr><a:defRPr sz="4000"/></a:lvl1pPr>"""),
            defaultTextStyle = style("p:defaultTextStyle", """<a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr>"""),
        )

        assertEquals(40.0, sizeOf(sources), 0.0)
    }

    @Test
    fun `the presentation default outranks the built-in default`() {
        val sources = TextStyleSources(
            defaultTextStyle = style("p:defaultTextStyle", """<a:lvl1pPr><a:defRPr sz="1500"/></a:lvl1pPr>"""),
        )

        assertEquals(15.0, sizeOf(sources), 0.0)
    }

    @Test
    fun `an undeclared size falls back to 18 pt, the theme minor font and tx1`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body("""<a:p><a:r><a:t>x</a:t></a:r></a:p>"""),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single()

        assertEquals(18.0, run.properties.sizePt, 0.0)
        assertEquals("Calibri", run.properties.latinTypeface)
        assertEquals(Rgba.BLACK, (run.properties.fill as ScenePaint.Solid).color)
    }

    @Test
    fun `a paragraph resolves the list level style its lvl names`() {
        val sources = TextStyleSources(listStyles = listOf(level("""<a:defRPr sz="3000"/>""", level = 3)))

        val paragraph = resolver.resolve(
            body("""<a:p><a:pPr lvl="2"/><a:r><a:rPr sz="1000"/><a:t>x</a:t></a:r></a:p>"""),
            sources,
        ).paragraphs.single()

        assertEquals(2, paragraph.level)
        assertEquals(30.0, paragraph.endProperties.sizePt, 0.0)
    }

    @Test
    fun `an empty paragraph takes its height from endParaRPr`() {
        val sources = TextStyleSources(masterStyle = style("p:bodyStyle", """<a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr>"""))

        val paragraph = resolver.resolve(
            body("""<a:p><a:endParaRPr sz="4400"/></a:p>"""),
            sources,
        ).paragraphs.single()

        assertTrue(paragraph.runs.isEmpty())
        assertEquals(44.0, paragraph.endProperties.sizePt, 0.0)
    }

    @Test
    fun `runs, breaks and fields keep their order and the cached field text`() {
        val paragraph = resolver.resolve(
            body(
                """<a:p>""" +
                    """<a:fld type="slidenum"><a:rPr sz="1200"/><a:t>5</a:t></a:fld>""" +
                    """<a:br><a:rPr sz="1300"/></a:br>""" +
                    """<a:r><a:t>tail</a:t></a:r>""" +
                    """</a:p>""",
            ),
        ).paragraphs.single()

        val field = paragraph.runs[0] as TextRun.Field
        assertEquals("slidenum", field.type)
        assertEquals("5", field.text)
        assertEquals(12.0, field.properties.sizePt, 0.0)
        assertEquals(13.0, (paragraph.runs[1] as TextRun.Break).properties.sizePt, 0.0)
        assertEquals("tail", (paragraph.runs[2] as TextRun.Text).text)
    }

    @Test
    fun `every run property its rPr declares comes through`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr sz="800" b="1" i="1" u="dbl" strike="dblStrike" cap="all" """ +
                    """baseline="30000" spc="150" kern="1200" lang="en-GB" altLang="fr-FR">""" +
                    """<a:solidFill><a:srgbClr val="FF0000"/></a:solidFill>""" +
                    """<a:highlight><a:srgbClr val="FFFF00"/></a:highlight>""" +
                    """<a:latin typeface="+mj-lt"/><a:ea typeface="MS Gothic"/>""" +
                    """<a:cs typeface="Arial"/><a:sym typeface="Wingdings"/>""" +
                    """<a:ln w="12700"><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill></a:ln>""" +
                    """<a:effectLst><a:outerShdw blurRad="63500"/></a:effectLst>""" +
                    """</a:rPr><a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single().properties

        assertEquals(8.0, run.sizePt, 0.0)
        assertTrue(run.bold)
        assertTrue(run.italic)
        assertEquals("dbl", run.underline.style)
        assertTrue(run.underline.visible)
        assertEquals(TextStrike.DOUBLE, run.strike)
        assertEquals(TextCaps.ALL, run.caps)
        assertEquals(0.3, run.baseline, 0.0)
        assertEquals(1.5, run.spacingPt, 0.0)
        assertEquals(12.0, run.kerningPt, 0.0)
        assertEquals("en-GB", run.language)
        assertEquals("fr-FR", run.alternativeLanguage)
        assertEquals(Rgba(255, 0, 0), (run.fill as ScenePaint.Solid).color)
        assertEquals(Rgba(255, 255, 0), run.highlight)
        assertEquals("Calibri Light", run.latinTypeface)
        assertEquals("MS Gothic", run.eastAsianTypeface)
        assertEquals("Arial", run.complexScriptTypeface)
        assertEquals("Wingdings", run.symbolTypeface)
        assertEquals(1.0, run.outline!!.widthPt, 0.0)
        assertEquals(Rgba(0, 255, 0), (run.outline!!.paint as ScenePaint.Solid).color)
        assertEquals(5.0, run.shadow!!.blurPt, 0.0)
        assertEquals(0.0, run.shadow!!.distancePt, 0.0)
        assertEquals(Rgba.BLACK, run.shadow!!.color)
    }

    @Test
    fun `a text outline takes its width, gradient paint and direction`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr><a:ln w="25400">""" +
                    """<a:gradFill><a:gsLst><a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs>""" +
                    """<a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs></a:gsLst>""" +
                    """<a:lin ang="5400000"/></a:gradFill>""" +
                    """</a:ln></a:rPr><a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single().properties

        val outline = run.outline!!
        assertEquals(2.0, outline.widthPt, 0.0)
        assertEquals(2, (outline.paint as ScenePaint.Linear).gradient.stops.size)
    }

    @Test
    fun `a text outline with no fill or noFill draws nothing`() {
        val (theme, colors) = theme()
        val sources = TextStyleSources(theme = theme, colors = colors)

        val bare = resolver.resolve(
            body("""<a:p><a:r><a:rPr><a:ln w="12700"/></a:rPr><a:t>x</a:t></a:r></a:p>"""),
            sources,
        ).paragraphs.single().runs.single().properties
        val none = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr><a:ln w="12700"><a:noFill/></a:ln></a:rPr>""" +
                    """<a:t>x</a:t></a:r></a:p>""",
            ),
            sources,
        ).paragraphs.single().runs.single().properties

        assertNull(bare.outline)
        assertNull(none.outline)
    }

    @Test
    fun `an outer shadow keeps its offset, direction and colour`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr><a:effectLst><a:outerShdw blurRad="6350" dist="25400" """ +
                    """dir="2700000"><a:srgbClr val="336699"/></a:outerShdw></a:effectLst></a:rPr>""" +
                    """<a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single().properties

        val shadow = run.shadow!!
        assertEquals(0.5, shadow.blurPt, 0.0)
        assertEquals(2.0, shadow.distancePt, 0.0)
        assertEquals(45.0, shadow.directionDeg, 0.0)
        assertEquals(Rgba(0x33, 0x66, 0x99), shadow.color)
    }

    @Test
    fun `an effect list with no outer shadow adds nothing`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr><a:effectLst><a:glow rad="6350"/></a:effectLst></a:rPr>""" +
                    """<a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single().properties

        assertNull(run.shadow)
    }

    @Test
    fun `a hyperlink run carries its relationship and the theme link colours`() {
        val (theme, colors) = theme()
        val run = resolver.resolve(
            body(
                """<a:p><a:r><a:rPr><a:hlinkClick r:id="rId7" action="ppaction://hlinksldjump"/></a:rPr>""" +
                    """<a:t>link</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().runs.single().properties

        assertEquals("rId7", run.hyperlink!!.relationshipId)
        assertEquals("ppaction://hlinksldjump", run.hyperlink!!.action)
        assertEquals(Rgba(0x05, 0x63, 0xC1), run.hyperlink!!.color)
        assertEquals(Rgba(0x95, 0x4F, 0x72), run.hyperlink!!.visitedColor)
    }

    @Test
    fun `paragraph properties cascade from the paragraph through the list levels`() {
        val sources = TextStyleSources(
            listStyles = listOf(
                level(
                    """<a:lnSpc><a:spcPct val="120000"/></a:lnSpc>""" +
                        """<a:spcBef><a:spcPts val="600"/></a:spcBef>""" +
                        """<a:spcAft><a:spcPct val="50000"/></a:spcAft>""" +
                        """<a:tabLst><a:tab pos="914400" algn="dec"/><a:tab pos="1828800"/></a:tabLst>""",
                ),
            ),
            masterStyle = style(
                "p:bodyStyle",
                """<a:lvl1pPr marL="342900" marR="114300" indent="-285750" defTabSz="914400" """ +
                    """rtl="1" algn="justLow" fontAlgn="base" eaLnBrk="0" latinLnBrk="0" """ +
                    """hangingPunct="1"><a:buChar char="•"/></a:lvl1pPr>""",
            ),
        )

        val properties = resolver.resolve(body("""<a:p><a:r><a:t>x</a:t></a:r></a:p>"""), sources)
            .paragraphs.single().properties

        assertEquals(342900.0, properties.marginLeftEmu!!, 0.0)
        assertEquals(114300.0, properties.marginRightEmu!!, 0.0)
        assertEquals(-285750.0, properties.indentEmu!!, 0.0)
        assertEquals(914400.0, properties.defaultTabSizeEmu!!, 0.0)
        assertTrue(properties.rightToLeft)
        assertEquals(TextAlignment.JUSTIFY_LOW, properties.alignment)
        assertEquals(TextFontAlignment.BASELINE, properties.fontAlignment)
        assertFalse(properties.eastAsianLineBreak)
        assertFalse(properties.latinLineBreak)
        assertTrue(properties.hangingPunctuation)
        assertEquals(120.0, properties.lineSpacing!!.percent!!, 0.0)
        assertEquals(6.0, properties.spaceBefore!!.points!!, 0.0)
        assertEquals(50.0, properties.spaceAfter!!.percent!!, 0.0)
        assertEquals(914400.0, properties.tabStops[0].positionEmu, 0.0)
        assertEquals(TextTabAlignment.DECIMAL, properties.tabStops[0].alignment)
        assertEquals(TextTabAlignment.LEFT, properties.tabStops[1].alignment)
        assertEquals("•", (properties.bullet as TextBullet.Character).char)
    }

    @Test
    fun `a character bullet keeps its font, colour and size`() {
        val (theme, colors) = theme()
        val bullet = resolver.resolve(
            body(
                """<a:p><a:pPr><a:buFont typeface="Wingdings"/><a:buChar char="Ø"/>""" +
                    """<a:buClr><a:srgbClr val="112233"/></a:buClr>""" +
                    """<a:buSzPct val="90000"/></a:pPr><a:r><a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().properties.bullet as TextBullet.Character

        assertEquals("Ø", bullet.char)
        assertEquals("Wingdings", bullet.font)
        assertEquals(Rgba(0x11, 0x22, 0x33), bullet.color)
        assertEquals(90.0, (bullet.size as TextBulletSize.Percent).value, 0.0)
    }

    @Test
    fun `an auto number bullet keeps its scheme and startAt`() {
        val bullet = resolver.resolve(
            body("""<a:p><a:pPr><a:buAutoNum type="romanUcPeriod" startAt="3"/></a:pPr><a:r><a:t>x</a:t></a:r></a:p>"""),
        ).paragraphs.single().properties.bullet as TextBullet.Numbering

        assertEquals("romanUcPeriod", bullet.scheme)
        assertEquals(3, bullet.startAt)
    }

    @Test
    fun `buNone clears a bullet an inherited level set`() {
        val sources = TextStyleSources(
            masterStyle = style("p:bodyStyle", """<a:lvl1pPr><a:buChar char="•"/></a:lvl1pPr>"""),
        )

        val bullet = resolver.resolve(
            body("""<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>x</a:t></a:r></a:p>"""),
            sources,
        ).paragraphs.single().properties.bullet

        assertEquals(TextBullet.None, bullet)
    }

    @Test
    fun `buClrTx, buSzTx and buFontTx hand the bullet back to the text`() {
        val (theme, colors) = theme()
        val bullet = resolver.resolve(
            body(
                """<a:p><a:pPr><a:buClrTx/><a:buSzTx/><a:buFontTx/><a:buChar char="-"/></a:pPr>""" +
                    """<a:r><a:t>x</a:t></a:r></a:p>""",
            ),
            TextStyleSources(theme = theme, colors = colors),
        ).paragraphs.single().properties.bullet as TextBullet.Character

        assertEquals(Rgba.BLACK, bullet.color)
        assertEquals(TextBulletSize.Inherit, bullet.size)
        assertNull(bullet.font)
    }

    @Test
    fun `a picture bullet keeps its blip and size in points`() {
        val bullet = resolver.resolve(
            body(
                """<a:p><a:pPr><a:buBlip><a:blip r:embed="rId3"/></a:buBlip><a:buSzPts val="1200"/></a:pPr>""" +
                    """<a:r><a:t>x</a:t></a:r></a:p>""",
            ),
        ).paragraphs.single().properties.bullet as TextBullet.Picture

        assertEquals("rId3", bullet.image!!.attribute(NAMESPACE_R, "embed"))
        assertEquals(12.0, (bullet.size as TextBulletSize.Points).value, 0.0)
    }

    @Test
    fun `bodyPr default insets, anchor and wrap come out when the file states nothing`() {
        val properties = resolver.resolve(body("""<a:p/>""")).properties

        assertEquals(TextInsets.DEFAULT, properties.insets)
        assertEquals(TextAnchor.TOP, properties.anchor)
        assertEquals(TextWrap.SQUARE, properties.wrap)
        assertEquals(TextVertical.HORZ, properties.vertical)
        assertEquals(TextAutofit.None, properties.autofit)
        assertEquals(1, properties.columnCount)
    }

    @Test
    fun `every bodyPr property comes through`() {
        val textProperties = resolver.resolve(
            body(
                """<a:bodyPr lIns="1" tIns="2" rIns="3" bIns="4" anchor="dist" anchorCtr="1" wrap="none" """ +
                    """vert="vert270" rot="5400000" rtlCol="1" numCol="2" spcCol="100000" upright="1" """ +
                    """compatLnSpc="1"><a:prstTxWarp prst="textArchUp"><a:avLst>""" +
                    """<a:gd name="adj" fmla="val 1000"/></a:avLst></a:prstTxWarp>""" +
                    """<a:normAutofit fontScale="62500" lnSpcReduction="20000"/></a:bodyPr>""" +
                    """<a:p/>""",
            ),
        ).properties

        assertEquals(TextInsets(1.0, 2.0, 3.0, 4.0), textProperties.insets)
        assertEquals(TextAnchor.DISTRIBUTED, textProperties.anchor)
        assertTrue(textProperties.anchorCenter)
        assertEquals(TextWrap.NONE, textProperties.wrap)
        assertEquals(TextVertical.VERT_270, textProperties.vertical)
        assertEquals(90.0, textProperties.rotation, 0.0)
        assertTrue(textProperties.rightToLeftColumns)
        assertEquals(2, textProperties.columnCount)
        assertEquals(100000.0, textProperties.columnSpacingEmu!!, 0.0)
        assertEquals(TextWarp("textArchUp", mapOf("adj" to "val 1000")), textProperties.warp)
        assertTrue(textProperties.upright)
        assertTrue(textProperties.compatibleLineSpacing)
        val autofit = textProperties.autofit as TextAutofit.Normal
        assertEquals(62.5, autofit.fontScale, 0.0)
        assertEquals(20.0, autofit.lineSpacingReduction, 0.0)
    }

    @Test
    fun `a shape's own bodyPr outranks the layout's and the master's`() {
        val shape = node("""<a:bodyPr anchor="b" $NAMESPACES/>""")
        val layout = node("""<a:bodyPr anchor="ctr" $NAMESPACES/>""")
        val master = node("""<a:bodyPr lIns="7" $NAMESPACES/>""")

        val properties = resolver.resolve(
            body("""<a:p/>"""),
            bodyProperties = listOf(shape, layout, master),
        ).properties

        assertEquals(TextAnchor.BOTTOM, properties.anchor)
        assertEquals(7.0, properties.insets.left, 0.0)
    }

    private companion object {
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"

        const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

        const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        const val NAMESPACE_R = R

        const val NAMESPACES = """xmlns:a="$A" xmlns:p="$P" xmlns:r="$R""""
    }
}
