package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
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

    private companion object {
        const val MAX_EDGE = 4_096
        const val MAX_PIXELS = 64_000_000L
    }
}
