package com.papyrus.app.viewer

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A glyph box and a link rectangle over the same page point must land on the same page fractions,
 * whatever the crop origin and `/Rotate` (glyphs crop-relative, `/Rect` raw). No text: fonts need assets.
 */
class PdfCropBoxGeometryTest {

    /**
     * Built from the corner array directly: this port's four-argument [PDRectangle] takes width and
     * height, not a corner.
     */
    private fun rect(llx: Float, lly: Float, urx: Float, ury: Float): PDRectangle =
        PDRectangle(COSArray().apply {
            add(COSFloat(llx))
            add(COSFloat(lly))
            add(COSFloat(urx))
            add(COSFloat(ury))
        })

    /** A 600x800 media box with a 200x100 crop box whose lower-left is ([originX], [originY]). */
    private fun page(rotation: Int, originX: Float, originY: Float): PDPage =
        PDPage(rect(0f, 0f, 600f, 800f)).apply {
            cropBox = rect(originX, originY, originX + 200f, originY + 100f)
            setRotation(rotation)
        }

    /** What the text extractor does with a glyph box given crop-relative corners. */
    private fun glyphBox(frame: PdfPageFrame, x0: Float, y0: Float, x1: Float, y1: Float): NormRect {
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        frame.include(x0, y0, box)
        frame.include(x1, y0, box)
        frame.include(x0, y1, box)
        frame.include(x1, y1, box)
        return NormRect(box[0], box[1], box[2], box[3])
    }

    private fun assertSame(expected: NormRect, actual: NormRect?, message: String) {
        requireNotNull(actual) { "$message: no rectangle" }
        assertEquals("$message left", expected.left, actual.left, TOLERANCE)
        assertEquals("$message top", expected.top, actual.top, TOLERANCE)
        assertEquals("$message right", expected.right, actual.right, TOLERANCE)
        assertEquals("$message bottom", expected.bottom, actual.bottom, TOLERANCE)
    }

    @Test
    fun `a glyph box and a link rectangle over the same point agree for every origin and rotation`() {
        for (rotation in ROTATIONS) {
            for ((originX, originY) in ORIGINS) {
                val page = page(rotation, originX, originY)
                // The same area, 50..90 across and 40..60 up of the crop box, in each input space.
                val glyph = glyphBox(PdfPageFrame.forGlyphs(page), 50f, 40f, 90f, 60f)
                val link = PdfPageFrame.of(page).toNorm(originX + 50f, originY + 40f, originX + 90f, originY + 60f)
                assertSame(glyph, link, "rotation $rotation, origin ($originX, $originY)")
            }
        }
    }

    @Test
    fun `glyph boxes do not depend on where the crop box starts`() {
        // The glyph frame must not fold an origin out a second time: PdfBox already did.
        for (rotation in ROTATIONS) {
            val atZero = glyphBox(PdfPageFrame.forGlyphs(page(rotation, 0f, 0f)), 20f, 50f, 60f, 75f)
            val offset = glyphBox(PdfPageFrame.forGlyphs(page(rotation, 100f, 200f)), 20f, 50f, 60f, 75f)
            assertSame(atZero, offset, "rotation $rotation")
        }
    }

    @Test
    fun `glyph boxes turn with the page`() {
        // The same 20..60 across, 50..75 up area as the link tests, so both factories pin one table.
        val expected = mapOf(
            0 to NormRect(0.1f, 0.25f, 0.3f, 0.5f),
            90 to NormRect(0.5f, 0.1f, 0.75f, 0.3f),
            180 to NormRect(0.7f, 0.5f, 0.9f, 0.75f),
            270 to NormRect(0.25f, 0.7f, 0.5f, 0.9f),
        )
        for ((rotation, rect) in expected) {
            assertSame(rect, glyphBox(PdfPageFrame.forGlyphs(page(rotation, 100f, 200f)), 20f, 50f, 60f, 75f), "rotation $rotation")
        }
    }

    @Test
    fun `a negative or oversized rotation normalises to a quarter turn`() {
        val expected = glyphBox(PdfPageFrame.forGlyphs(page(270, 0f, 0f)), 20f, 50f, 60f, 75f)
        assertSame(expected, glyphBox(PdfPageFrame.forGlyphs(page(-90, 0f, 0f)), 20f, 50f, 60f, 75f), "-90")
        assertSame(expected, glyphBox(PdfPageFrame.forGlyphs(page(630, 0f, 0f)), 20f, 50f, 60f, 75f), "630")
    }

    private companion object {
        const val TOLERANCE = 1e-6f
        val ROTATIONS = intArrayOf(0, 90, 180, 270)
        val ORIGINS = listOf(0f to 0f, 100f to 200f, 37.5f to 12.25f)
    }
}
