package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.PresetFillMode
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePreset
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class ConnectorGeometryTest {

    private val geometry = PresetGeometry(
        open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
        newParserFactory = { KXmlParser() },
    )

    private val shapes = ShapeGeometry(geometry)

    private val frame = SceneRect(0.0, 0.0, 1_000.0, 500.0)

    @Test
    fun `a straight connector draws its stored two point path`() {
        val path = shapes.outline(connector("straightConnector1"))!!.paths.single()

        assertEquals(
            listOf(PresetSegment.MoveTo(0.0, 0.0), PresetSegment.LineTo(1_000.0, 500.0)),
            path.segments,
        )
        assertEquals(PresetFillMode.NONE, path.fill)
        assertTrue(path.stroke)
    }

    @Test
    fun `a bent connector draws its stored right angles`() {
        val path = shapes.outline(connector("bentConnector2"))!!.paths.single()

        assertEquals(
            listOf(
                PresetSegment.MoveTo(0.0, 0.0),
                PresetSegment.LineTo(1_000.0, 0.0),
                PresetSegment.LineTo(1_000.0, 500.0),
            ),
            path.segments,
        )
    }

    @Test
    fun `a bent connector adjust moves its bend`() {
        val bend = shapes.outline(connector("bentConnector3"))!!
            .paths.single().segments.filterIsInstance<PresetSegment.LineTo>()[0]
        val narrower = shapes.outline(
            connector("bentConnector3", adjusts = mapOf("adj1" to "val 25000")),
        )!!.paths.single().segments.filterIsInstance<PresetSegment.LineTo>()[0]

        assertEquals(500.0, bend.x, 1e-9)
        assertEquals(250.0, narrower.x, 1e-9)
    }

    @Test
    fun `a curved connector draws cubics that end at the far corner`() {
        val segments = shapes.outline(connector("curvedConnector2"))!!.paths.single().segments

        assertTrue(segments.any { it is PresetSegment.CubicTo })
        assertEquals(PresetSegment.MoveTo(0.0, 0.0), segments.first())
        val last = segments.last() as PresetSegment.CubicTo
        assertEquals(1_000.0, last.x, 1e-9)
        assertEquals(500.0, last.y, 1e-9)
    }

    @Test
    fun `the paint transform flips a connector about its frame centre`() {
        val segments = shapes.outline(connector("straightConnector1", flipped = true))!!.paths.single().segments
        val transform = Affine.forShape(frame, flipH = true, flipV = true)
        val start = segments.first() as PresetSegment.MoveTo
        val end = segments.last() as PresetSegment.LineTo

        assertEquals(ScenePoint(1_000.0, 500.0), transform.map(ScenePoint(start.x, start.y)))
        assertEquals(ScenePoint(0.0, 0.0), transform.map(ScenePoint(end.x, end.y)))
    }

    private fun connector(
        preset: String,
        adjusts: Map<String, String> = emptyMap(),
        flipped: Boolean = false,
    ): SceneConnector = SceneConnector(
        id = 1,
        name = preset,
        frame = frame,
        start = ScenePoint(frame.x, frame.y),
        end = ScenePoint(frame.right, frame.bottom),
        preset = ScenePreset(preset, adjusts, flipH = flipped, flipV = flipped),
    )
}
