package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.PathBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.PresetFillMode
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class PresetGeometryTest {

    private val geometry = PresetGeometry(
        open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
        newParserFactory = { KXmlParser() },
    )

    private val builder = PathBuilder()

    @Test
    fun `a path with its own coordinate space is scaled onto the shape`() {
        val outline = builder.build(shape("flowChartProcess"), 400.0, 300.0)!!
        assertEquals(
            listOf(
                PresetSegment.MoveTo(0.0, 0.0),
                PresetSegment.LineTo(400.0, 0.0),
                PresetSegment.LineTo(400.0, 300.0),
                PresetSegment.LineTo(0.0, 300.0),
                PresetSegment.Close,
            ),
            outline.paths.single().segments,
        )
    }

    @Test
    fun `a shape keeps its default text rectangle`() {
        val outline = builder.build(shape("triangle"), 400.0, 300.0)!!
        assertEquals(SceneRect(100.0, 150.0, 200.0, 150.0), outline.textRect)
    }

    @Test
    fun `an adjust value moves the text rectangle`() {
        val outline = builder.build(shape("triangle"), 400.0, 300.0, mapOf("adj" to "val 0"))!!
        assertEquals(SceneRect(0.0, 150.0, 200.0, 150.0), outline.textRect)
    }

    @Test
    fun `an out of range adjust is clamped`() {
        val outline = builder.build(shape("triangle"), 400.0, 300.0, mapOf("adj" to "val 200000"))!!
        assertEquals(SceneRect(200.0, 150.0, 200.0, 150.0), outline.textRect)
    }

    @Test
    fun `a preset without a text rectangle uses the whole box`() {
        val outline = builder.build(shape("line"), 400.0, 300.0)!!
        assertEquals(SceneRect(0.0, 0.0, 400.0, 300.0), outline.textRect)
    }

    @Test
    fun `fill modes tint shade or drop the shape fill`() {
        val color = Rgba(100, 200, 50)
        assertEquals(color, PresetFillMode.NORM.adjust(color))
        assertNull(PresetFillMode.NONE.adjust(color))
        assertEquals(Rgba(162, 222, 132), PresetFillMode.LIGHTEN.adjust(color))
        assertEquals(Rgba(131, 211, 91), PresetFillMode.LIGHTEN_LESS.adjust(color))
        assertEquals(Rgba(60, 120, 30), PresetFillMode.DARKEN.adjust(color))
        assertEquals(Rgba(80, 160, 40), PresetFillMode.DARKEN_LESS.adjust(color))
        assertEquals(PresetFillMode.DARKEN_LESS, PresetFillMode.of("darkenLess"))
        assertEquals(PresetFillMode.NORM, PresetFillMode.of("unknown"))
    }

    @Test
    fun `a parsed preset is cached and an unknown name is absent`() {
        assertSame(shape("triangle"), shape("triangle"))
        assertNull(geometry.shape("notAPreset"))
    }

    @Test
    fun `a path can declare no stroke`() {
        val outline = builder.build(shape("accentCallout1"), 400.0, 300.0)!!
        assertTrue(outline.paths.any { !it.stroke })
        assertTrue(outline.paths.any { it.stroke })
    }

    private fun shape(name: String) =
        requireNotNull(geometry.shape(name)) { "missing preset $name" }
}
