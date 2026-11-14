package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.DeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeTreeParser
import io.github.pixelrick420.papyrus.viewer.pptx.SlideScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTreeParserRobustnessTest {

    private fun read(deck: Deck): PptxPackage =
        PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)

    private fun parse(deck: Deck, limits: PptxLimits = PptxLimits()): SlideScene = ShapeTreeParser(OfficeFixtures::parser, limits)
        .parse(read(deck), "ppt/slides/slide1.xml", SceneSize(12_192_000.0, 6_858_000.0))

    private fun parseRaw(shapes: String, limits: PptxLimits = PptxLimits()): SlideScene =
        parse(pptx { slide(); part("ppt/slides/slide1.xml", SlideXml.slide(shapes)) }, limits)

    private fun parseDeck(deck: Deck, limits: PptxLimits = PptxLimits()): DeckScenes {
        val pkg = read(deck)
        val presentation = PresentationParser(OfficeFixtures::parser).parse(pkg.part(pkg.mainPart)!!)
        return ShapeTreeParser(OfficeFixtures::parser, limits).parseAll(pkg, presentation)
    }

    @Test
    fun `an infinite or NaN extent leaves the frame unset but keeps the shape`() {
        val scene = parseRaw(
            SlideXml.shape(2, "Huge", x = "1e400", y = "NaN", width = "1e400", height = "0"),
        )

        val node = scene.scene.nodes.single()

        assertNull(node.frame)
        assertFalse(scene.incomplete)
    }

    @Test
    fun `a negative or maximal but finite extent is kept as it was read`() {
        val scene = parseRaw(
            SlideXml.shape(
                2,
                "Extreme",
                x = "-100",
                y = "-9223372036854775808",
                width = "9223372036854775807",
                height = "0",
            ),
        )

        val frame = scene.scene.nodes.single().frame
        assertNotNull(frame)
        assertEquals(SceneRect(-100.0, Long.MIN_VALUE.toDouble(), Long.MAX_VALUE.toDouble(), 0.0), frame)
        assertFalse(scene.incomplete)
    }

    @Test
    fun `a group nested past the group limit is cut and the slide is marked incomplete`() {
        var shapes = SlideXml.shape(1_000, "Core")
        repeat(40) { level -> shapes = SlideXml.group(100 + level, "g$level", shapes) }

        val scene = parseRaw(shapes)

        var node: SceneNode? = scene.scene.nodes.single()
        var levels = 0
        while (node is SceneGroup) {
            levels++
            node = node.children.firstOrNull()
        }
        assertEquals(32, levels)
        assertTrue(scene.incomplete)
    }

    @Test
    fun `a slide is truncated at the shape limit and marked incomplete`() {
        val scene = parseRaw(manyShapes(5), PptxLimits(maxShapes = 3))

        assertEquals(3, scene.scene.nodes.size)
        assertTrue(scene.incomplete)
    }

    @Test
    fun `ten thousand shapes fit and the ten thousand and first is dropped`() {
        val scene = parseRaw(manyShapes(10_001))

        assertEquals(10_000, scene.scene.nodes.size)
        assertTrue(scene.incomplete)
        assertEquals(2, scene.scene.nodes.first().id)
        assertEquals(10_001, scene.scene.nodes.last().id)
    }

    @Test
    fun `a shape past the xml depth limit is dropped and what came before it survives`() {
        val tooDeep = SlideXml.shape(
            3,
            "Too deep",
            spPrExtra = "<a:extLst>".repeat(210) + "</a:extLst>".repeat(210),
        )

        val scene = parseRaw(SlideXml.shape(2, "First") + tooDeep + SlideXml.shape(4, "After"))

        assertEquals(listOf("First"), scene.scene.nodes.map { it.name })
        assertTrue(scene.incomplete)
    }

    @Test
    fun `a part over the element budget keeps the shapes read before the breach`() {
        val oneShape = SlideXml.slide(SlideXml.shape(2, "First"))
        val scene = parseRaw(
            SlideXml.shape(2, "First") + SlideXml.shape(3, "Second"),
            PptxLimits(maxXmlElements = elementCount(oneShape) + 5),
        )

        assertEquals(listOf("First"), scene.scene.nodes.map { it.name })
        assertTrue(scene.incomplete)
    }

    @Test
    fun `a slide part of garbage bytes is dropped and the rest of the deck still parses`() {
        val deck = pptx {
            slide { shape(Preset.RECT, 0, 0, 1, 1, name = "Only") }
            slide()
            part("ppt/slides/slide2.xml", "not xml at all".toByteArray())
        }

        val scenes = parseDeck(deck)

        assertEquals(2, scenes.slides.size)
        assertEquals(listOf("Only"), scenes.slides[0].scene.nodes.map { it.name })
        assertTrue(scenes.slides[1].incomplete)
        assertTrue(scenes.incomplete)
    }

    private fun manyShapes(count: Int): String =
        (2 until count + 2).joinToString("") { id -> SlideXml.shape(id, "s$id") }

    private fun elementCount(xml: ByteArray): Int =
        Regex("<(?![!?/])").findAll(xml.toString(Charsets.UTF_8)).count()
}
