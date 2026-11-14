package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.DeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.GraphicKind
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxFormatException
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeTreeParser
import io.github.pixelrick420.papyrus.viewer.pptx.SlideScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTreeParserTest {

    private fun read(deck: Deck): PptxPackage =
        PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)

    private fun parse(
        deck: Deck,
        part: String = "ppt/slides/slide1.xml",
        limits: PptxLimits = PptxLimits(),
    ): SlideScene = ShapeTreeParser(OfficeFixtures::parser, limits)
        .parse(read(deck), part, SceneSize(12_192_000.0, 6_858_000.0))

    private fun parseRaw(shapes: String, tail: String = ""): SlideScene =
        parse(pptx { slide(); part("ppt/slides/slide1.xml", SlideXml.slide(shapes, tail)) })

    private fun parseDeck(deck: Deck): DeckScenes {
        val pkg = read(deck)
        val presentation = PresentationParser(OfficeFixtures::parser).parse(pkg.part(pkg.mainPart)!!)
        return ShapeTreeParser(OfficeFixtures::parser).parseAll(pkg, presentation)
    }

    @Test
    fun `shapes parse in paint order with their ids, names and frames`() {
        val deck = pptx {
            slide {
                shape(Preset.RECT, 100, 200, 300, 400, name = "First")
                shape(Preset.ELLIPSE, 500, 600, 700, 800, name = "Second")
            }
        }

        val scene = parse(deck)

        assertEquals(listOf(2, 3), scene.scene.nodes.map { it.id })
        assertEquals(listOf("First", "Second"), scene.scene.nodes.map { it.name })
        assertEquals(SceneRect(100.0, 200.0, 300.0, 400.0), scene.scene.nodes[0].frame)
        assertEquals(SceneRect(500.0, 600.0, 700.0, 800.0), scene.scene.nodes[1].frame)
        assertFalse(scene.incomplete)
    }

    @Test
    fun `a hidden placeholder keeps its flag and its identity`() {
        val shape = SlideXml.shape(
            id = 7,
            name = "Hidden body",
            cNvPr = """<p:cNvPr id="7" name="Hidden body" hidden="1"/>""",
            nvPr = """<p:ph type="body" idx="2"/>""",
        )

        val node = parseRaw(shape).scene.nodes.single() as SceneShape

        assertTrue(node.hidden)
        assertEquals("body", node.placeholder?.type)
        assertEquals(2, node.placeholder?.idx)
    }

    @Test
    fun `a placeholder without an index defaults to zero`() {
        val deck = pptx { slide { textPlaceholder(null) { para("plain") } } }

        val node = parse(deck).scene.nodes.single() as SceneShape

        assertNull(node.placeholder?.type)
        assertEquals(0, node.placeholder?.idx)
    }

    @Test
    fun `a text body is kept whole and left unresolved for the text parser`() {
        val deck = pptx { slide { textBox(0, 0, 100, 100) { para("hello") } } }

        val node = parse(deck).scene.nodes.single() as SceneShape

        assertNull(node.text)
        val body = node.textBody
        assertNotNull(body)
        assertEquals(
            "hello",
            body?.child(OoxmlNamespace.A, "p")?.child(OoxmlNamespace.A, "r")?.child(OoxmlNamespace.A, "t")?.text,
        )
    }

    @Test
    fun `a style reference is kept whole for the paint pass`() {
        val style = """<p:style><a:lnRef idx="3"><a:schemeClr val="accent2"/></a:lnRef></p:style>"""

        val node = parseRaw(SlideXml.shape(2, "Styled", style = style)).scene.nodes.single() as SceneShape

        assertEquals("3", node.style?.child(OoxmlNamespace.A, "lnRef")?.attribute("idx"))
    }

    @Test
    fun `a click hyperlink keeps its relationship and action`() {
        val cNvPr = """<p:cNvPr id="9" name="Link">""" +
            """<a:hlinkClick r:id="rId9" action="ppaction://hlinksldjump"/></p:cNvPr>"""

        val node = parseRaw(SlideXml.shape(9, "Link", cNvPr = cNvPr)).scene.nodes.single()

        assertEquals("rId9", node.hyperlink?.relationshipId)
        assertEquals("ppaction://hlinksldjump", node.hyperlink?.action)
    }

    @Test
    fun `a picture keeps the relationship of its blip and its frame`() {
        val deck = pptx { slide { picture(OfficeFixtures.PNG_1X1, 100, 200, 3_000, 4_000) } }

        val node = parse(deck).scene.nodes.single() as ScenePicture

        assertEquals("rId2", node.relationshipId)
        assertEquals(SceneRect(100.0, 200.0, 3_000.0, 4_000.0), node.frame)
    }

    @Test
    fun `a group keeps both its own frame and the space its children are laid out in`() {
        val child = SlideXml.shape(11, "Child", x = "150", y = "250", width = "500", height = "500")
        val group = SlideXml.group(
            10,
            "Group",
            child,
            x = 1_000,
            y = 2_000,
            width = 3_000,
            height = 4_000,
            childX = 100,
            childY = 200,
            childW = 300,
            childH = 400,
        )

        val node = parseRaw(group).scene.nodes.single() as SceneGroup

        assertEquals(SceneRect(1_000.0, 2_000.0, 3_000.0, 4_000.0), node.frame)
        assertEquals(SceneRect(100.0, 200.0, 300.0, 400.0), node.childFrame)
        val inside = node.children.single() as SceneShape
        assertEquals(SceneRect(150.0, 250.0, 500.0, 500.0), inside.frame)
    }

    @Test
    fun `a connector keeps its preset, adjusts, flips and endpoints`() {
        val connector = SlideXml.connector(
            20,
            "Diagonal",
            x = 100,
            y = 200,
            width = 300,
            height = 400,
            preset = "line",
            flip = """ flipH="1"""",
            adjusts = """<a:gd name="adj" fmla="val 50000"/>""",
        )

        val node = parseRaw(connector).scene.nodes.single() as SceneConnector

        assertEquals("line", node.preset?.preset)
        assertEquals(mapOf("adj" to "val 50000"), node.preset?.adjusts)
        assertTrue(node.preset?.flipH == true)
        assertFalse(node.preset?.flipV == true)
        assertEquals(ScenePoint(400.0, 200.0), node.start)
        assertEquals(ScenePoint(100.0, 600.0), node.end)
    }

    @Test
    fun `a graphic frame maps the graphic data uri to its kind`() {
        val uris = listOf(
            "http://schemas.openxmlformats.org/drawingml/2006/table" to GraphicKind.TABLE,
            "http://schemas.openxmlformats.org/drawingml/2006/chart" to GraphicKind.CHART,
            "http://schemas.openxmlformats.org/drawingml/2006/diagram" to GraphicKind.SMART_ART,
            "http://schemas.openxmlformats.org/presentationml/2006/ole" to GraphicKind.OLE_OBJECT,
            "http://schemas.openxmlformats.org/presentationml/2006/unknown" to GraphicKind.OTHER,
        )
        val shapes = uris.mapIndexed { index, (uri, _) -> SlideXml.graphicFrame(10 + index, "Frame$index", uri) }

        val nodes = parseRaw(shapes.joinToString("")).scene.nodes

        assertEquals(uris.map { it.second }, nodes.map { (it as SceneGraphicFrame).kind })
    }

    @Test
    fun `a chart's graphic frame keeps the relationship the chart names`() {
        val chart = """<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId7"/>"""

        val node = parseRaw(
            SlideXml.graphicFrame(10, "Chart", "http://schemas.openxmlformats.org/drawingml/2006/chart", chart),
        ).scene.nodes.single() as SceneGraphicFrame

        assertEquals(GraphicKind.CHART, node.kind)
        assertEquals("rId7", node.relationshipId)
    }

    @Test
    fun `a table graphic frame takes its frame from the graphic frame transform`() {
        val deck = pptx { slide { table(100, 200, listOf(1_000, 2_000)) { row(500, "a", "b") } } }

        val node = parse(deck).scene.nodes.single() as SceneGraphicFrame

        assertEquals(GraphicKind.TABLE, node.kind)
        assertEquals(SceneRect(100.0, 200.0, 3_000.0, 500.0), node.frame)
    }

    @Test
    fun `slides parse in presentation order and a broken slide is dropped whole`() {
        val broken = ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sld xmlns:a="${SlideXml.A}" xmlns:r="${SlideXml.R}" xmlns:p="${SlideXml.P}">""" +
            "<p:cSld></p:wrong></p:sld>").toByteArray()
        val deck = pptx {
            slide { shape(Preset.RECT, 0, 0, 1, 1, name = "First") }
            slide { shape(Preset.RECT, 0, 0, 1, 1, name = "Second") }
            slide { shape(Preset.RECT, 0, 0, 1, 1, name = "Third") }
            part("ppt/slides/slide2.xml", broken)
        }

        val scenes = parseDeck(deck)

        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide3.xml"),
            scenes.slides.map { it.part },
        )
        assertEquals(SceneSize(12_192_000.0, 6_858_000.0), scenes.slides[0].scene.size)
        assertFalse(scenes.slides[0].incomplete)
        assertEquals(listOf("First"), scenes.slides[0].scene.nodes.map { it.name })
        assertTrue(scenes.slides[1].incomplete)
        assertTrue(scenes.slides[1].scene.nodes.isEmpty())
        assertEquals(listOf("Third"), scenes.slides[2].scene.nodes.map { it.name })
        assertTrue(scenes.incomplete)
    }

    @Test
    fun `a clean deck is not marked incomplete`() {
        val scenes = parseDeck(pptx { slide { shape(Preset.RECT, 0, 0, 1, 1) } })

        assertFalse(scenes.incomplete)
        assertEquals(1, scenes.slides.size)
    }

    @Test
    fun `transitions and timing add no nodes`() {
        val tail = """<p:transition><p:fade/></p:transition>""" +
            """<p:timing><p:tnLst><p:par><p:cTn id="1"/></p:par></p:tnLst></p:timing>"""

        val nodes = parseRaw(SlideXml.shape(2, "Final"), tail).scene.nodes

        assertEquals(listOf("Final"), nodes.map { it.name })
    }

    @Test(expected = PptxFormatException::class)
    fun `a part that is not a slide is rejected`() {
        val deck = pptx {
            slide()
            part("ppt/slides/slide1.xml", """<p:notes xmlns:p="${SlideXml.P}"/>""".toByteArray())
        }

        parse(deck)
    }

    @Test(expected = PptxFormatException::class)
    fun `a slide without a shape tree is rejected`() {
        val deck = pptx {
            slide()
            part("ppt/slides/slide1.xml", """<p:sld xmlns:p="${SlideXml.P}"><p:cSld/></p:sld>""".toByteArray())
        }

        parse(deck)
    }
}

/** Raw slide XML for the shapes the fixture builders cannot express: groups, connectors, hostile values. */
internal object SlideXml {
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun slide(shapes: String, tail: String = ""): ByteArray =
        ("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""" +
            shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" + tail + "</p:sld>")
            .toByteArray()

    fun shape(
        id: Int,
        name: String,
        x: String = "0",
        y: String = "0",
        width: String = "914400",
        height: String = "914400",
        preset: String = "rect",
        flip: String = "",
        cNvPr: String = """<p:cNvPr id="$id" name="$name"/>""",
        nvPr: String = "",
        spPrExtra: String = "",
        style: String = "",
    ): String =
        """<p:sp><p:nvSpPr>$cNvPr<p:cNvSpPr/><p:nvPr>$nvPr</p:nvPr></p:nvSpPr>""" +
            """<p:spPr><a:xfrm$flip><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm>""" +
            """<a:prstGeom prst="$preset"><a:avLst/></a:prstGeom>$spPrExtra</p:spPr>$style""" +
            """<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>"""

    fun customShape(
        id: Int,
        name: String,
        geometry: String,
        x: Long = 0,
        y: Long = 0,
        width: Long = 914_400,
        height: Long = 914_400,
    ): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm>""" +
            geometry + """</p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>"""

    fun group(
        id: Int,
        name: String,
        children: String,
        x: Long = 0,
        y: Long = 0,
        width: Long = 914_400,
        height: Long = 914_400,
        childX: Long = 0,
        childY: Long = 0,
        childW: Long = 914_400,
        childH: Long = 914_400,
    ): String =
        """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="$id" name="$name"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/>""" +
            """<a:chOff x="$childX" y="$childY"/><a:chExt cx="$childW" cy="$childH"/></a:xfrm></p:grpSpPr>""" +
            children + "</p:grpSp>"

    fun connector(
        id: Int,
        name: String,
        x: Long = 0,
        y: Long = 0,
        width: Long = 914_400,
        height: Long = 914_400,
        preset: String = "line",
        flip: String = "",
        adjusts: String = "",
    ): String =
        """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="$id" name="$name"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>""" +
            """<p:spPr><a:xfrm$flip><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm>""" +
            """<a:prstGeom prst="$preset"><a:avLst>$adjusts</a:avLst></a:prstGeom></p:spPr></p:cxnSp>"""

    fun graphicFrame(
        id: Int,
        name: String,
        uri: String,
        inner: String = "",
        x: Long = 0,
        y: Long = 0,
        width: Long = 914_400,
        height: Long = 914_400,
    ): String =
        """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$id" name="$name"/>""" +
            """<p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>""" +
            """<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></p:xfrm>""" +
            """<a:graphic><a:graphicData uri="$uri">$inner</a:graphicData></a:graphic></p:graphicFrame>"""
}
