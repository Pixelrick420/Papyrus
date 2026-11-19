package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.MasterLayoutParser
import io.github.pixelrick420.papyrus.viewer.pptx.MasterLayouts
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterLayoutParserTest {

    private fun parse(deck: Deck): MasterLayouts =
        MasterLayoutParser(OfficeFixtures::parser)
            .parse(PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser))

    @Test
    fun `a fixture deck's master carries its theme, colour map and text styles`() {
        val deck = pptx {
            theme { accent1("4472C4") }
            master {
                clrMap("bg1" to "dk1")
                titleStyle { sz(4400) }
                bodyStyle { sz(2000) }
            }
            layout("Title and Content")
        }

        val master = parse(deck).masters.single()

        assertEquals("ppt/slideMasters/slideMaster1.xml", master.part)
        assertEquals("ppt/theme/theme1.xml", master.themePart)
        assertNotNull(master.theme?.colors?.get("accent1"))
        assertEquals("dk1", master.colorMap.target("bg1"))
        assertEquals("dk1", master.colorMap.target("tx1"))
        assertNotNull(master.textStyles.titleStyle)
        assertNotNull(master.textStyles.bodyStyle)
        assertNotNull(master.textStyles.otherStyle)
    }

    @Test
    fun `a master keeps every layout in the order its list declares`() {
        val deck = pptx {
            layout("Title")
            layout("Content")
            layout("Blank")
        }

        val parsed = parse(deck)
        val master = parsed.masters.single()

        assertEquals(listOf("Title", "Content", "Blank"), master.layouts.map { it.name })
        assertEquals(3, parsed.layouts.size)
    }

    @Test
    fun `a layout keeps its name, master, showMasterShapes and inherited colour map`() {
        val deck = pptx {
            layout("Title and Content", showMasterSp = false)
        }

        val layout = parse(deck).layouts.single()

        assertEquals("Title and Content", layout.name)
        assertEquals("ppt/slideMasters/slideMaster1.xml", layout.masterPart)
        assertFalse(layout.showMasterShapes)
        assertNull(layout.colorMapOverride)
    }

    @Test
    fun `an omitted showMasterSp defaults to showing master shapes`() {
        val layout = parse(pptx { layout("Blank") }).layouts.single()

        assertTrue(layout.showMasterShapes)
    }

    @Test
    fun `a layout's overrideClrMapping re-points slots instead of inheriting`() {
        val deck = pptx {
            layout("Blank")
            part("ppt/slideLayouts/slideLayout1.xml", layoutWithOverride("dk1", "lt1"))
        }

        val layout = parse(deck).layouts.single()

        assertEquals("dk1", layout.colorMapOverride?.target("bg1"))
        assertEquals("lt1", layout.colorMapOverride?.target("tx1"))
        assertFalse(layout.showMasterShapes)
    }

    @Test
    fun `placeholder tables answer by index and by type`() {
        val deck = pptx {
            layout("Title and Content") {
                placeholder(Type.TITLE, x = 838_200, y = 365_125, w = 10_515_600, h = 1_325_563)
                placeholder(Type.BODY, idx = 1, x = 838_200, y = 1_825_563, w = 10_515_600, h = 4_354_437)
            }
        }

        val table = parse(deck).layouts.single().placeholders

        val title = table.byType("title")
        assertNotNull(title)
        assertSame(title, table.byIdx(0))
        assertEquals(0, title?.identity?.idx)
        assertEquals("title", title?.identity?.type)
        assertEquals(838_200.0, title?.frame?.x ?: 0.0, 0.0)
        assertEquals(1_325_563.0, title?.frame?.height ?: 0.0, 0.0)

        val body = table.byIdx(1)
        assertNotNull(body)
        assertSame(body, table.byType("body"))
        assertNull(table.byIdx(7))
        assertNull(table.byType("obj"))
    }

    @Test
    fun `a master's placeholders are indexed as well as a layout's`() {
        val deck = pptx {
            master {
                placeholder(Type.CENTER_TITLE, idx = 0, x = 0, y = 0, w = 9_144_000, h = 1_000_000)
                placeholder(Type.SUBTITLE, idx = 1)
            }
            layout("Title Slide")
        }

        val master = parse(deck).masters.single()

        assertEquals("ctrTitle", master.placeholders.byIdx(0)?.identity?.type)
        assertEquals(1, master.placeholders.byType("subTitle")?.identity?.idx)
    }

    @Test
    fun `several slides can name one layout without it being parsed twice`() {
        val deck = pptx {
            layout("Title and Content")
            slide(layout = 0) {}
            slide(layout = 0) {}
        }

        val parsed = parse(deck)

        assertEquals(1, parsed.layouts.size)
        assertSame(parsed.layouts.single(), parsed.layoutsOf(parsed.masters.single()).single())
    }

    @Test
    fun `a master with no theme relationship parses without a theme`() {
        val deck = pptx {
            layout("Blank")
            part("ppt/slideMasters/_rels/slideMaster1.xml.rels", masterRelsWithoutTheme())
        }

        val master = parse(deck).masters.single()

        assertNull(master.themePart)
        assertNull(master.theme)
        assertEquals(1, master.layouts.size)
    }

    @Test
    fun `a master part that is not a slide master is skipped`() {
        val deck = pptx {
            layout("Blank")
            part("ppt/slideMasters/slideMaster1.xml", """<p:sldLayout xmlns:p="${OoxmlNamespace.P}"/>""".toByteArray())
        }

        assertTrue(parse(deck).masters.isEmpty())
    }

    private fun layoutWithOverride(bg1: String, tx1: String): ByteArray =
        (
            """<p:sldLayout xmlns:p="${OoxmlNamespace.P}" xmlns:a="${OoxmlNamespace.A}" showMasterSp="0">""" +
                "<p:cSld><p:spTree/></p:cSld>" +
                """<p:clrMapOvr><a:overrideClrMapping bg1="$bg1" tx1="$tx1"/></p:clrMapOvr>""" +
                "</p:sldLayout>"
            ).toByteArray()

    private fun masterRelsWithoutTheme(): ByteArray =
        (
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="${OoxmlNamespace.R}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
                "</Relationships>"
            ).toByteArray()
}
