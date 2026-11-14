package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.DeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.GradientStop
import io.github.pixelrick420.papyrus.viewer.pptx.LinearGradient
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.RadialGradient
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FillsTest {

    private fun resolve(deck: Deck, limits: PptxLimits = PptxLimits()): DeckScenes {
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        val presentation = PresentationParser(OfficeFixtures::parser)
            .parse(deck.parts.getValue("ppt/presentation.xml"))
        return StyleResolver(OfficeFixtures::parser, limits).resolveAll(pkg, presentation)
    }

    private fun scene(deck: Deck, limits: PptxLimits = PptxLimits()): Scene =
        resolve(deck, limits).slides.single().scene

    private fun shape(scene: Scene, name: String): SceneShape =
        scene.nodes.filterIsInstance<SceneShape>().single { it.name == name }

    private fun paintOf(deck: Deck, limits: PptxLimits = PptxLimits()): ScenePaint =
        shape(scene(deck, limits), "filled").format?.fill ?: error("no fill")

    private fun linearOf(deck: Deck, limits: PptxLimits = PptxLimits()): LinearGradient {
        val paint = paintOf(deck, limits)
        assertTrue("not a linear gradient: $paint", paint is ScenePaint.Linear)
        return (paint as ScenePaint.Linear).gradient
    }

    private fun radialOf(deck: Deck, limits: PptxLimits = PptxLimits()): RadialGradient {
        val paint = paintOf(deck, limits)
        assertTrue("not a radial gradient: $paint", paint is ScenePaint.Radial)
        return (paint as ScenePaint.Radial).gradient
    }

    private val stops =
        """<a:gsLst>""" +
            """<a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs>""" +
            """<a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs>""" +
            """</a:gsLst>"""

    private fun filled(xml: String, style: Boolean = false): Deck = pptx {
        layout("Blank")
        slide {
            shape(Preset.RECT, 0, 0, 100, 100, name = "filled") {
                fillRaw(xml)
                if (style) style()
            }
        }
    }

    @Test
    fun `a solid fill keeps its colour and alpha`() {
        val paint = paintOf(
            filled("""<a:solidFill><a:srgbClr val="336699"><a:alpha val="50000"/></a:srgbClr></a:solidFill>"""),
        )

        assertEquals(Rgba(0x33, 0x66, 0x99, 128), (paint as ScenePaint.Solid).color)
    }

    @Test
    fun `noFill and grpFill paint nothing`() {
        assertSame(ScenePaint.None, paintOf(filled("<a:noFill/>")))
        assertSame(ScenePaint.None, paintOf(filled("<a:grpFill/>", style = true)))
    }

    @Test
    fun `an explicit fill beats the style reference`() {
        val paint = paintOf(filled("""<a:solidFill><a:srgbClr val="00FF00"/></a:solidFill>""", style = true))

        assertEquals(Rgba(0x00, 0xFF, 0x00), (paint as ScenePaint.Solid).color)
    }

    @Test
    fun `a linear gradient keeps its angle, stops and per-stop alpha`() {
        val gradient = linearOf(
            filled(
                """<a:gradFill><a:gsLst>""" +
                    """<a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs>""" +
                    """<a:gs pos="50000"><a:schemeClr val="accent1"><a:alpha val="50000"/></a:schemeClr></a:gs>""" +
                    """<a:gs pos="100000"><a:srgbClr val="0000FF"/></a:gs>""" +
                    """</a:gsLst><a:lin ang="2700000" scaled="0"/></a:gradFill>""",
            ),
        )

        assertEquals(45.0, gradient.angleDegrees, 1e-9)
        assertFalse(gradient.scaled)
        assertEquals(listOf(0.0, 0.5, 1.0), gradient.stops.map { it.offset })
        assertEquals(Rgba(0xFF, 0x00, 0x00), gradient.stops[0].color)
        assertEquals(Rgba(0x44, 0x72, 0xC4, 128), gradient.stops[1].color)
        assertEquals(Rgba(0x00, 0x00, 0xFF), gradient.stops[2].color)
    }

    @Test
    fun `a scaled angle is stretched by the frame's aspect`() {
        val frame = SceneRect(0.0, 0.0, 200.0, 100.0)
        val stops = listOf(GradientStop(0.0, Rgba.BLACK), GradientStop(1.0, Rgba.WHITE))

        assertEquals(26.565051, LinearGradient(45.0, scaled = true, stops = stops).angleIn(frame), 1e-6)
        assertEquals(45.0, LinearGradient(45.0, scaled = false, stops = stops).angleIn(frame), 1e-9)
        assertEquals(45.0, LinearGradient(45.0, scaled = true, stops = stops).angleIn(null), 1e-9)
    }

    @Test
    fun `stops are ordered by position and capped by the package limit`() {
        val deck = filled(
            """<a:gradFill><a:gsLst>""" +
                """<a:gs pos="100000"><a:srgbClr val="FF0000"/></a:gs>""" +
                """<a:gs pos="0"><a:srgbClr val="0000FF"/></a:gs>""" +
                """<a:gs pos="50000"><a:srgbClr val="00FF00"/></a:gs>""" +
                """<a:gs pos="25000"><a:srgbClr val="FFFFFF"/></a:gs>""" +
                """</a:gsLst><a:lin ang="0"/></a:gradFill>""",
        )

        val gradient = linearOf(deck, PptxLimits(maxGradientStops = 2))

        assertEquals(listOf(0.0, 1.0), gradient.stops.map { it.offset })
        assertEquals(listOf(Rgba(0x00, 0x00, 0xFF), Rgba(0xFF, 0x00, 0x00)), gradient.stops.map { it.color })
    }

    @Test
    fun `a theme fill style that is a gradient resolves through the reference`() {
        val deck = pptx {
            theme {
                fillStyles(
                    """<a:fillStyleLst><a:gradFill><a:gsLst>""" +
                        """<a:gs pos="0"><a:schemeClr val="phClr"/></a:gs>""" +
                        """<a:gs pos="100000"><a:srgbClr val="000000"/></a:gs>""" +
                        """</a:gsLst><a:lin ang="5400000"/></a:gradFill></a:fillStyleLst>""",
                )
            }
            layout("Blank")
            slide {
                shape(Preset.RECT, 0, 0, 100, 100, name = "filled") {
                    style(fillRef = 1, scheme = "accent1")
                }
            }
        }

        val gradient = linearOf(deck)

        assertEquals(90.0, gradient.angleDegrees, 1e-9)
        assertTrue(gradient.scaled)
        assertEquals(Rgba(0x44, 0x72, 0xC4), gradient.stops.first().color)
    }

    @Test
    fun `a circle path gradient takes its focus from fillToRect`() {
        val gradient = radialOf(
            filled(
                """<a:gradFill>$stops""" +
                    """<a:path path="circle"><a:fillToRect l="0" t="10000" r="50000" b="0"/></a:path>""" +
                    """</a:gradFill>""",
            ),
        )

        assertEquals(0.25, gradient.centerX, 1e-9)
        assertEquals(0.55, gradient.centerY, 1e-9)
        assertEquals(0.25, gradient.innerRadiusX, 1e-9)
        assertEquals(0.45, gradient.innerRadiusY, 1e-9)
        assertEquals(listOf(Rgba(0xFF, 0x00, 0x00), Rgba(0x00, 0x00, 0xFF)), gradient.stops.map { it.color })
    }

    @Test
    fun `a circle path gradient without fillToRect focuses at the centre`() {
        val gradient = radialOf(filled("""<a:gradFill>$stops<a:path path="circle"/></a:gradFill>"""))

        assertEquals(0.5, gradient.centerX, 1e-9)
        assertEquals(0.5, gradient.centerY, 1e-9)
        assertEquals(0.0, gradient.innerRadiusX, 1e-9)
        assertEquals(0.0, gradient.innerRadiusY, 1e-9)
    }

    @Test
    fun `a present fillToRect leaves an omitted side at the tile edge`() {
        val gradient = radialOf(
            filled("""<a:gradFill>$stops<a:path path="circle"><a:fillToRect l="50000"/></a:path></a:gradFill>"""),
        )

        assertEquals(0.5, gradient.left, 1e-9)
        assertEquals(0.0, gradient.right, 1e-9)
        assertEquals(0.75, gradient.centerX, 1e-9)
    }

    @Test
    fun `rect and shape path gradients stay complex`() {
        for (path in listOf("rect", "shape")) {
            val deck = filled(
                """<a:gradFill>$stops""" +
                    """<a:path path="$path"><a:fillToRect l="25000" t="25000" r="25000" b="25000"/></a:path>""" +
                    """</a:gradFill>""",
            )

            assertTrue(path, paintOf(deck) is ScenePaint.Complex)
        }
    }
}
