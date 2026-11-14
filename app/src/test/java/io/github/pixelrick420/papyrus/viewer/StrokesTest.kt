package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.ArrowEnd
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowSize
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowType
import io.github.pixelrick420.papyrus.viewer.pptx.DashStop
import io.github.pixelrick420.papyrus.viewer.pptx.DeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCompound
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin
import io.github.pixelrick420.papyrus.viewer.pptx.Strokes
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokesTest {

    private fun resolve(deck: Deck): DeckScenes {
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        val presentation = PresentationParser(OfficeFixtures::parser)
            .parse(deck.parts.getValue("ppt/presentation.xml"))
        return StyleResolver(OfficeFixtures::parser).resolveAll(pkg, presentation)
    }

    private fun scene(deck: Deck): Scene = resolve(deck).slides.single().scene

    private fun shape(scene: Scene, name: String): SceneShape =
        scene.nodes.filterIsInstance<SceneShape>().single { it.name == name }

    private fun formatOf(shape: SceneShape): SceneFormat = shape.format ?: error("no format on ${shape.name}")

    private fun line(dash: String? = null, widthEmu: Double? = null): SceneLine =
        SceneLine(widthEmu = widthEmu, paint = null, dash = dash)

    @Test
    fun `a zero width is a hairline of at least one device pixel`() {
        assertEquals(9_525.0, Strokes.resolvedWidthEmu(0.0, 9_525.0), 0.0)
        assertEquals(9_525.0, Strokes.resolvedWidthEmu(null, 9_525.0), 0.0)
        assertEquals(12_700.0, Strokes.resolvedWidthEmu(12_700.0, 9_525.0), 0.0)
    }

    @Test
    fun `cap and compound names map to their drawing model`() {
        assertEquals(StrokeCap.FLAT, StrokeCap.of(null))
        assertEquals(StrokeCap.ROUND, StrokeCap.of("rnd"))
        assertEquals(StrokeCap.SQUARE, StrokeCap.of("sq"))
        assertEquals(StrokeCompound.SINGLE, StrokeCompound.of(null))
        assertEquals(StrokeCompound.DOUBLE, StrokeCompound.of("dbl"))
        assertEquals(StrokeCompound.THICK_THIN, StrokeCompound.of("thickThin"))
        assertEquals(StrokeCompound.THIN_THICK, StrokeCompound.of("thinThick"))
        assertEquals(StrokeCompound.TRIPLE, StrokeCompound.of("tri"))
    }

    @Test
    fun `all eleven preset dashes scale with the line width`() {
        assertEquals(11, Strokes.presetNames().size)
        assertTrue(Strokes.dashPattern(line("solid"), 2_000.0).isEmpty())
        assertEquals(listOf(8_000.0, 6_000.0), Strokes.dashPattern(line("dash"), 2_000.0))
        assertEquals(listOf(2_000.0, 2_000.0), Strokes.dashPattern(line("sysDot"), 2_000.0))
        assertEquals(
            listOf(16_000.0, 6_000.0, 2_000.0, 6_000.0, 2_000.0, 6_000.0),
            Strokes.dashPattern(line("lgDashDotDot"), 2_000.0),
        )
        assertTrue(Strokes.dashPattern(line("unknown"), 2_000.0).isEmpty())
    }

    @Test
    fun `a custom dash is a percentage of the line width and wins over a preset`() {
        val custom = SceneLine(
            widthEmu = 1_000.0,
            paint = null,
            dash = "dash",
            customDash = listOf(DashStop(2.5, 1.5)),
        )

        assertEquals(listOf(2_500.0, 1_500.0), Strokes.dashPattern(custom, 1_000.0))
    }

    @Test
    fun `compound lines split the width into bands around the centre`() {
        assertEquals(listOf(1_200.0 to 0.0), bands(StrokeCompound.SINGLE))
        assertEquals(listOf(400.0 to -400.0, 400.0 to 400.0), bands(StrokeCompound.DOUBLE))
        assertEquals(listOf(600.0 to -300.0, 300.0 to 450.0), bands(StrokeCompound.THICK_THIN))
        assertEquals(listOf(300.0 to -450.0, 600.0 to 300.0), bands(StrokeCompound.THIN_THICK))
        assertEquals(
            listOf(400.0 to -400.0, 400.0 to 0.0, 400.0 to 400.0),
            bands(StrokeCompound.TRIPLE),
        )
    }

    private fun bands(compound: StrokeCompound): List<Pair<Double, Double>> =
        Strokes.bands(compound, 1_200.0).map { it.widthEmu to it.offsetEmu }

    @Test
    fun `arrow size classes scale against the line width`() {
        val end = ArrowEnd(ArrowType.TRIANGLE, ArrowSize.LARGE, ArrowSize.SMALL)

        assertEquals(2_000.0, end.lengthEmu(1_000.0), 0.0)
        assertEquals(5_000.0, end.widthEmu(1_000.0), 0.0)
        assertEquals(2_000.0, end.trimEmu(1_000.0), 0.0)
        assertEquals(0.0, ArrowEnd(ArrowType.ARROW).trimEmu(1_000.0), 0.0)
        assertEquals(0.0, ArrowEnd.NONE.trimEmu(1_000.0), 0.0)
        assertNull(Strokes.arrowGeometry(ArrowEnd.NONE, ScenePoint(0.0, 0.0), ScenePoint(1.0, 0.0), 1_000.0))
    }

    @Test
    fun `a triangle head points along the line and spans its width class`() {
        val end = ArrowEnd(ArrowType.TRIANGLE, ArrowSize.MEDIUM, ArrowSize.SMALL)
        val head = Strokes.arrowGeometry(end, ScenePoint(10_000.0, 0.0), ScenePoint(2.0, 0.0), 1_000.0)!!

        assertTrue(head.closed)
        assertTrue(head.filled)
        assertEquals(ScenePoint(10_000.0, 0.0), head.points.first())
        assertEquals(ScenePoint(8_000.0, 1_500.0), head.points[1])
        assertEquals(ScenePoint(8_000.0, -1_500.0), head.points[2])
    }

    @Test
    fun `stealth diamond oval and open arrow keep their distinct shapes`() {
        val stealth = Strokes.arrowGeometry(
            ArrowEnd(ArrowType.STEALTH, ArrowSize.MEDIUM, ArrowSize.LARGE),
            ScenePoint(5_000.0, 500.0),
            ScenePoint(0.0, 1.0),
            1_000.0,
        )!!
        assertEquals(4, stealth.points.size)
        assertEquals(ScenePoint(5_000.0, -2_500.0), stealth.points[2])

        val diamond = Strokes.arrowGeometry(
            ArrowEnd(ArrowType.DIAMOND),
            ScenePoint(5_000.0, 500.0),
            ScenePoint(0.0, 1.0),
            1_000.0,
        )!!
        assertEquals(4, diamond.points.size)
        assertEquals(ScenePoint(5_000.0, -2_500.0), diamond.points[2])

        val oval = Strokes.arrowGeometry(
            ArrowEnd(ArrowType.OVAL),
            ScenePoint(5_000.0, 500.0),
            ScenePoint(0.0, 1.0),
            1_000.0,
        )!!
        assertEquals(16, oval.points.size)

        val arrow = Strokes.arrowGeometry(
            ArrowEnd(ArrowType.ARROW),
            ScenePoint(5_000.0, 500.0),
            ScenePoint(0.0, 1.0),
            1_000.0,
        )!!
        assertFalse(arrow.closed)
        assertFalse(arrow.filled)
        assertEquals(3, arrow.points.size)
    }

    @Test
    fun `the shaft is shortened at both ends so a filled head is not pierced`() {
        val points = listOf(ScenePoint(0.0, 0.0), ScenePoint(10_000.0, 0.0))
        val tail = SceneLine(
            widthEmu = 1_000.0,
            paint = null,
            dash = null,
            tailEnd = ArrowEnd(ArrowType.TRIANGLE),
        )
        val both = SceneLine(
            widthEmu = 1_000.0,
            paint = null,
            dash = null,
            headEnd = ArrowEnd(ArrowType.TRIANGLE),
            tailEnd = ArrowEnd(ArrowType.TRIANGLE),
        )

        assertEquals(listOf(ScenePoint(0.0, 0.0), ScenePoint(7_000.0, 0.0)), Strokes.shaft(tail, points, 9_525.0))
        assertEquals(listOf(ScenePoint(3_000.0, 0.0), ScenePoint(7_000.0, 0.0)), Strokes.shaft(both, points, 9_525.0))
    }

    @Test
    fun `cap join compound dashes and arrows resolve from the shape properties`() {
        val deck = pptx {
            layout("Blank")
            slide {
                shape(Preset.RECT, 0, 0, 100, 100, name = "stroked") {
                    line(
                        rgb = "112233",
                        attributes = """ cap="rnd" cmpd="thickThin"""",
                        extra = """<a:miter lim="400000"/>""" +
                            """<a:custDash><a:ds d="200000" sp="100000"/></a:custDash>""" +
                            """<a:headEnd type="triangle" w="lg" len="sm"/>""" +
                            """<a:tailEnd type="stealth"/>""",
                    )
                }
            }
        }

        val stroked = formatOf(shape(scene(deck), "stroked")).line!!
        assertEquals(StrokeCap.ROUND, stroked.cap)
        assertEquals(StrokeJoin.MITER, stroked.join)
        assertEquals(400_000.0, stroked.miterLimit, 0.0)
        assertEquals(StrokeCompound.THICK_THIN, stroked.compound)
        assertEquals(listOf(DashStop(2.0, 1.0)), stroked.customDash)
        assertEquals(ArrowType.TRIANGLE, stroked.headEnd.type)
        assertEquals(ArrowSize.LARGE, stroked.headEnd.width)
        assertEquals(ArrowSize.SMALL, stroked.headEnd.length)
        assertEquals(ArrowType.STEALTH, stroked.tailEnd.type)
    }

    @Test
    fun `a head end that names no type does not inherit the theme's arrow`() {
        val deck = pptx {
            layout("Body") {
                placeholder(Type.BODY, idx = 1, decoration = {
                    line(rgb = "FF0000", extra = """<a:headEnd type="triangle"/>""")
                })
            }
            slide {
                placeholder(Type.BODY, idx = 1, decoration = {
                    line(rgb = "00FF00", extra = """<a:headEnd/>""")
                })
            }
        }

        val stroked = formatOf(scene(deck).nodes.filterIsInstance<SceneShape>().single { it.placeholder != null }).line!!
        assertEquals(ArrowType.NONE, stroked.headEnd.type)
    }
}
