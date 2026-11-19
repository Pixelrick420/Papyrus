package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.CustomGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PathBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeTreeParser
import io.github.pixelrick420.papyrus.viewer.pptx.SlideScene
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomGeometryTest {

    private val builder = PathBuilder()

    @Test
    fun `an inline path builds in shape coordinates`() {
        val outline = builder.build(shape(path(moveTo("l", "t") + lnTo("r", "b") + close())), 300.0, 200.0)!!

        assertEquals(
            listOf(
                PresetSegment.MoveTo(0.0, 0.0),
                PresetSegment.LineTo(300.0, 200.0),
                PresetSegment.Close,
            ),
            outline.paths.single().segments,
        )
    }

    @Test
    fun `a path with its own coordinate space is scaled onto the shape`() {
        val outline = builder.build(
            shape(path(moveTo("0", "0") + lnTo("100", "50"), """ w="100" h="50"""").trim()),
            300.0,
            200.0,
        )!!

        assertEquals(PresetSegment.LineTo(300.0, 200.0), outline.paths.single().segments.last())
    }

    @Test
    fun `an inline text rectangle is evaluated against the shape`() {
        val outline = builder.build(
            shape(path(moveTo("l", "t")), """<a:rect l="hc" t="vc" r="r" b="b"/>"""),
            300.0,
            200.0,
        )!!

        assertEquals(SceneRect(150.0, 100.0, 150.0, 100.0), outline.textRect)
    }

    @Test
    fun `an absent custom geometry is absent rather than invalid`() {
        val absent = CustomGeometry.read(properties("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""))

        assertEquals(CustomGeometry.Absent, absent)
    }

    @Test
    fun `the guide cap drops the geometry`() {
        val guides = """<a:gd name="a" fmla="val 1"/><a:gd name="b" fmla="val 2"/>"""
        val geometry = customGeometry(path(moveTo("l", "t")), guides = guides)

        assertTrue(read(geometry, PptxLimits(maxGuideEvaluations = 2)) is CustomGeometry.Shape)
        assertEquals(CustomGeometry.Invalid, read(geometry, PptxLimits(maxGuideEvaluations = 1)))
    }

    @Test
    fun `the path cap drops the geometry`() {
        val paths = path(moveTo("l", "t")) + path(moveTo("r", "b"))
        val geometry = customGeometry(paths)

        assertTrue(read(geometry, PptxLimits(maxPaths = 2)) is CustomGeometry.Shape)
        assertEquals(CustomGeometry.Invalid, read(geometry, PptxLimits(maxPaths = 1)))
    }

    @Test
    fun `the command cap drops the geometry`() {
        val paths = path(moveTo("l", "t") + lnTo("r", "t") + lnTo("r", "b"))
        val geometry = customGeometry(paths)

        assertTrue(read(geometry, PptxLimits(maxPathCommands = 3)) is CustomGeometry.Shape)
        assertEquals(CustomGeometry.Invalid, read(geometry, PptxLimits(maxPathCommands = 2)))
    }

    @Test
    fun `an arc may swing sixteen turns but not more`() {
        val sixteenTurns = 16L * 360L * 60_000L
        val allowed = customGeometry(path(moveTo("l", "t") + arcTo(swing = sixteenTurns.toString())))
        val over = customGeometry(path(moveTo("l", "t") + arcTo(swing = (sixteenTurns + 1).toString())))

        assertTrue(read(allowed) is CustomGeometry.Shape)
        assertEquals(CustomGeometry.Invalid, read(over))
    }

    @Test
    fun `an evaluated arc over the turn cap drops the outline`() {
        val custom = read(
            customGeometry(
                path(moveTo("l", "t") + arcTo(swing = "swing")),
                guides = """<a:gd name="swing" fmla="val 1000000000"/>""",
            ),
        ) as CustomGeometry.Shape

        assertNull(builder.build(custom.shape, 300.0, 200.0))
    }

    @Test
    fun `a non-finite coordinate drops the geometry`() {
        assertEquals(CustomGeometry.Invalid, read(customGeometry(path(moveTo("NaN", "0")))))
        assertEquals(CustomGeometry.Invalid, read(customGeometry(path(moveTo("l", "t") + lnTo("Infinity", "0")))))
    }

    @Test
    fun `a non-finite guide literal drops the geometry`() {
        assertEquals(
            CustomGeometry.Invalid,
            read(customGeometry(path(moveTo("l", "t")), guides = """<a:gd name="x" fmla="val 1e400"/>""")),
        )
    }

    @Test
    fun `an evaluated non-finite coordinate drops the outline`() {
        val custom = read(
            customGeometry(
                path(moveTo("l", "t") + lnTo("bad", "t")),
                guides = """<a:gd name="bad" fmla="*/ 1 1 0"/>""",
            ),
        ) as CustomGeometry.Shape

        assertNull(builder.build(custom.shape, 300.0, 200.0))
    }

    @Test
    fun `a missing path list drops the geometry`() {
        assertEquals(
            CustomGeometry.Invalid,
            read("""<a:custGeom><a:avLst/><a:gdLst/></a:custGeom>"""),
        )
    }

    @Test
    fun `an unknown path command drops the geometry`() {
        assertEquals(CustomGeometry.Invalid, read(customGeometry(path("<a:unknown/>"))))
    }

    @Test
    fun `a command with the wrong number of points drops the geometry`() {
        val move = """<a:moveTo><a:pt x="l" y="t"/><a:pt x="r" y="b"/></a:moveTo>"""

        assertEquals(CustomGeometry.Invalid, read(customGeometry(path(move))))
    }

    @Test
    fun `an arc missing an attribute drops the geometry`() {
        val arc = """<a:arcTo wR="w" hR="h" stAng="0"/>"""

        assertEquals(CustomGeometry.Invalid, read(customGeometry(path(moveTo("l", "t") + arc))))
    }

    @Test
    fun `a shape over a cap is dropped whole and the slide is marked incomplete`() {
        val tooManyPaths = SlideXml.customShape(
            3,
            "Huge",
            customGeometry(path(moveTo("l", "t")) + path(moveTo("r", "b"))),
        )

        val scene = parseRaw(SlideXml.shape(2, "First") + tooManyPaths, PptxLimits(maxPaths = 1))

        assertEquals(listOf("First"), scene.scene.nodes.map { it.name })
        assertTrue(scene.incomplete)
    }

    @Test
    fun `a valid custom shape keeps its inline geometry`() {
        val scene = parseRaw(
            SlideXml.customShape(2, "Custom", customGeometry(path(moveTo("l", "t") + lnTo("r", "b")))),
        )

        val shape = scene.scene.nodes.single() as SceneShape
        assertNotNull(shape.customGeometry)
        assertNull(shape.preset)
        assertFalse(scene.incomplete)
    }

    private fun read(geometry: String, limits: PptxLimits = PptxLimits()): CustomGeometry =
        CustomGeometry.read(properties(geometry), limits)

    private fun properties(geometry: String): XNode = requireNotNull(
        OoxmlReader(OfficeFixtures::parser).readTree(
            ("""<a:spPr xmlns:a="${SlideXml.A}">$geometry</a:spPr>""").toByteArray().inputStream(),
        ),
    )

    private fun shape(geometry: String, rect: String = "") =
        (read(customGeometry(geometry, rect = rect)) as CustomGeometry.Shape).shape

    private fun customGeometry(paths: String, guides: String = "", rect: String = ""): String =
        """<a:custGeom><a:avLst/><a:gdLst>$guides</a:gdLst><a:pathLst>$paths</a:pathLst>$rect</a:custGeom>"""

    private fun path(commands: String, attributes: String = ""): String =
        "<a:path$attributes>$commands</a:path>"

    private fun moveTo(x: String, y: String): String = point("moveTo", x, y)

    private fun lnTo(x: String, y: String): String = point("lnTo", x, y)

    private fun point(name: String, x: String, y: String): String =
        """<a:$name><a:pt x="$x" y="$y"/></a:$name>"""

    private fun arcTo(swing: String): String =
        """<a:arcTo wR="w" hR="h" stAng="0" swAng="$swing"/>"""

    private fun close(): String = "<a:close/>"

    private fun read(deck: Deck): PptxPackage =
        PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)

    private fun parseRaw(shapes: String, limits: PptxLimits = PptxLimits()): SlideScene =
        ShapeTreeParser(OfficeFixtures::parser, limits).parse(
            read(pptx { slide(); part("ppt/slides/slide1.xml", SlideXml.slide(shapes)) }),
            "ppt/slides/slide1.xml",
            SceneSize(12_192_000.0, 6_858_000.0),
        )
}
