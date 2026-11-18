package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.render.DrawOp
import io.github.pixelrick420.papyrus.viewer.pptx.render.PaintSpec
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmfRendererTest {

    @Test
    fun headerSetsBoundsAndCapsTheOutput() {
        val file = MetafileFixtures.emf()
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        assertEquals(4_096, image!!.bounds.width)
        assertEquals(3_277, image.bounds.height)
    }

    @Test
    fun outputNeverExceedsThePixelBudget() {
        val file = MetafileFixtures.emf()
        val image = Metafiles.parse(file, "emf", 4_096, 400)
        assertNotNull(image)
        val bounds = image!!.bounds
        org.junit.Assert.assertTrue(bounds.pixels <= 400)
        org.junit.Assert.assertTrue(bounds.width <= 4_096)
        org.junit.Assert.assertTrue(bounds.height <= 4_096)
    }

    @Test
    fun badSignatureIsRejected() {
        val file = MetafileFixtures.emf(signature = 0)
        assertNull(Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS))
    }

    @Test
    fun unsupportedAndTruncatedRecordsAreSkipped() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.record(0x79, 1, 2, 3),
                MetafileFixtures.record(0x21),
                byteArrayOf(0x22, 0x00, 0x00, 0x00, 0xFF.toByte()),
                MetafileFixtures.record(0x0E),
            ),
        )
        assertNotNull(Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS))
    }

    @Test
    fun windowAndViewportExtentsMapLogicalPointsIntoPixels() {
        val page = MetafilePage(100, 80, 0, 0, 100, 80, GdiMapping(200, 160, 0, 0, 100, 80, 0, 0))
        val mapped = deviceTransform(page, Affine.IDENTITY, page.mapping).map(50.0, 40.0)
        assertEquals(25.0, mapped.x, 1e-6)
        assertEquals(20.0, mapped.y, 1e-6)
    }

    @Test
    fun pensBrushesAndShapesProduceFillsAndStrokes() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.record(0x26, 0, 2, 0, 0x0000FF, 1),
                MetafileFixtures.record(0x27, 0, 0x00FF00, 0, 2),
                MetafileFixtures.record(0x25, 1),
                MetafileFixtures.record(0x25, 2),
                MetafileFixtures.record(0x2B, 10, 10, 20, 20),
                MetafileFixtures.record(0x2A, 30, 10, 50, 30),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val fills = image!!.displayList.ops.filterIsInstance<DrawOp.FillPath>()
        val strokes = image.displayList.ops.filterIsInstance<DrawOp.StrokePath>()
        assertEquals(2, fills.size)
        assertEquals(2, strokes.size)
        assertEquals(Rgba(0, 255, 0), (fills.first().paint as PaintSpec.Solid).color)
        assertEquals(Rgba(255, 0, 0), (strokes.first().stroke.paint as PaintSpec.Solid).color)
        org.junit.Assert.assertTrue(strokes.first().stroke.width > 2f)
    }

    @Test
    fun polygonPolylineAndBezierUseTheCurrentTransform() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.record(0x04, 0, 0, 10, 10, 2, 2, 2, 8, 8),
                MetafileFixtures.record(0x03, 0, 0, 10, 10, 3, 2, 2, 8, 2, 5, 8),
                MetafileFixtures.record(0x02, 0, 0, 10, 10, 4, 2, 2, 4, 8, 6, 2, 8, 8),
                MetafileFixtures.poly16(0x57, intArrayOf(0, 0, 10, 10), 1, 1, 9, 9),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val ops = image!!.displayList.ops
        val strokes = ops.filterIsInstance<DrawOp.StrokePath>()
        assertEquals(4, strokes.size)
        assertEquals(1, ops.filterIsInstance<DrawOp.FillPath>().size)
        val bezier = strokes[2].path.segments.filterIsInstance<PathSegment.CubicTo>()
        assertEquals(1, bezier.size)
        org.junit.Assert.assertTrue(bezier.single().x > strokes[2].path.segments.first().let {
            (it as PathSegment.MoveTo).x
        })
        val shortLine = strokes[3].path.segments.filterIsInstance<PathSegment.LineTo>().single()
        org.junit.Assert.assertTrue(shortLine.x > 0f)
    }

    @Test
    fun dualEmfRendersGdiRecordsAndIgnoresEmfPlusComments() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.emfPlusComment(),
                MetafileFixtures.record(0x2B, 10, 10, 20, 20),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        assertEquals(1, image!!.displayList.ops.filterIsInstance<DrawOp.FillPath>().size)
        assertNull(image.placeholderLabel)
    }

    @Test
    fun emfPlusOnlyGetsALabelledPlaceholder() {
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.emfPlusComment(),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        assertEquals("EMF+ image not displayed", image!!.placeholderLabel)
    }

    @Test
    fun dibBlitsCarryPixelsIntoTheDisplayList() {
        val info = MetafileFixtures.dibInfo(2, 2)
        val bits = byteArrayOf(
            0, 0, -1, 0, -1, 0, 0, 0,
            -1, 0, 0, 0, -1, -1, -1, 0,
        )
        val file = MetafileFixtures.emf(
            records = listOf(
                MetafileFixtures.setDibits(intArrayOf(10, 10, 30, 30), info, bits),
                MetafileFixtures.setDibits(intArrayOf(40, 10, 60, 30), info, bits, stretch = true),
                MetafileFixtures.record(0x0E),
            ),
        )
        val image = Metafiles.parse(file, "emf", MAX_EDGE, MAX_PIXELS)
        assertNotNull(image)
        val draws = image!!.displayList.ops.filterIsInstance<DrawOp.DrawImage>()
        assertEquals(2, draws.size)
        val pixels = image.dibs[draws.first().image.key]
        assertNotNull(pixels)
        assertEquals(2, pixels!!.width)
        assertEquals(-16_776_961, pixels.argb[0])
    }

    private companion object {
        const val MAX_EDGE = 4_096
        const val MAX_PIXELS = 64_000_000L
    }
}
