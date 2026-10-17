package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLocationTest {

    private fun page(unit: Int, fraction: Float = 0f) = ReadingLocation(LocationKind.PAGE, unit, fraction)

    private fun sample(location: ReadingLocation, count: Int = 100, atEnd: Boolean = false, zoom: Float = 1f) =
        PositionSample(location, count, atEnd, zoom)

    // ---- kinds ----

    @Test
    fun `every format with a viewer has a kind and only UNKNOWN has none`() {
        for (format in DocumentFormat.entries) {
            if (format == DocumentFormat.UNKNOWN) assertNull(LocationKind.forFormat(format)) else assertNotNull("$format", LocationKind.forFormat(format))
        }
    }

    @Test
    fun `formats map to the anchor that survives zoom and re-wrap`() {
        assertEquals(LocationKind.PAGE, LocationKind.forFormat(DocumentFormat.PDF))
        assertEquals(LocationKind.CHAR, LocationKind.forFormat(DocumentFormat.MARKDOWN))
        assertEquals(LocationKind.CHUNK, LocationKind.forFormat(DocumentFormat.TEXT))
        assertEquals(LocationKind.CHUNK, LocationKind.forFormat(DocumentFormat.CODE))
        for (format in listOf(DocumentFormat.DOC, DocumentFormat.DOCX, DocumentFormat.ODT)) {
            assertEquals(LocationKind.BLOCK, LocationKind.forFormat(format))
        }
    }

    @Test
    fun `kind codes are unique and an unknown stored code is ignored`() {
        assertEquals(LocationKind.entries.size, LocationKind.entries.map { it.code }.toSet().size)
        for (kind in LocationKind.entries) assertEquals(kind, LocationKind.fromCode(kind.code))
        assertNull(LocationKind.fromCode(0))
        assertNull(LocationKind.fromCode(99))
        assertNull(LocationKind.fromCode(-1))
    }

    // ---- clamping ----

    @Test
    fun `a location inside the document is left alone`() {
        assertEquals(page(12, 0.25f), page(12, 0.25f).clampedTo(200))
    }

    @Test
    fun `a page beyond the end lands on the start of the last page`() {
        assertEquals(page(199, 0f), page(500, 0.7f).clampedTo(200))
    }

    @Test
    fun `a negative unit lands on the start`() {
        assertEquals(page(0, 0f), page(-3, 0.9f).clampedTo(200))
    }

    @Test
    fun `NaN, negative and oversized fractions are repaired`() {
        assertEquals(0f, page(3, Float.NaN).clampedTo(10).fraction, 0f)
        assertEquals(0f, page(3, -0.5f).clampedTo(10).fraction, 0f)
        assertEquals(1f, page(3, 7f).clampedTo(10).fraction, 0f)
        assertEquals(1f, page(3, Float.POSITIVE_INFINITY).clampedTo(10).fraction, 0f)
    }

    @Test
    fun `an empty document clamps to the origin without throwing`() {
        assertEquals(page(0, 0f), page(5, 0.5f).clampedTo(0))
        assertEquals(page(0, 0f), page(5, 0.5f).clampedTo(-4))
    }

    @Test
    fun `a character offset has no fraction and is held inside the text`() {
        val at = ReadingLocation(LocationKind.CHAR, 1234, 0.8f)
        assertEquals(ReadingLocation(LocationKind.CHAR, 1234, 0f), at.clampedTo(5000))
        assertEquals(ReadingLocation(LocationKind.CHAR, 99, 0f), at.clampedTo(100))
    }

    @Test
    fun `clamping keeps the kind and is idempotent`() {
        val once = ReadingLocation(LocationKind.CHUNK, 900, 0.4f).clampedTo(30)
        assertEquals(LocationKind.CHUNK, once.kind)
        assertEquals(once, once.clampedTo(30))
    }

    @Test
    fun `a shrunken file re-clamps a stored position when the encoding changes chunk geometry`() {
        // 80 chunks became 55 after an encoding override: chunk 70 no longer exists.
        assertEquals(ReadingLocation(LocationKind.CHUNK, 54, 0f), ReadingLocation(LocationKind.CHUNK, 70, 0.3f).clampedTo(55))
    }

    // ---- progress ----

    @Test
    fun `pdf progress is page plus fraction over page count`() {
        assertEquals(0.4975f, page(99, 0.5f).progress(200), 1e-6f)
        assertEquals(0f, page(0, 0f).progress(200), 0f)
    }

    @Test
    fun `chunk and block progress count the fraction too`() {
        assertEquals(0.25f, ReadingLocation(LocationKind.CHUNK, 2, 0.5f).progress(10), 1e-6f)
        assertEquals(0.5f, ReadingLocation(LocationKind.BLOCK, 5, 0f).progress(10), 1e-6f)
    }

    @Test
    fun `markdown progress is the offset over the text length`() {
        assertEquals(0.45f, ReadingLocation(LocationKind.CHAR, 450, 0f).progress(1000), 1e-6f)
        assertEquals(0.45f, ReadingLocation(LocationKind.CHAR, 450, 0.99f).progress(1000), 1e-6f)
    }

    @Test
    fun `progress is held to 0 to 1 whatever was stored`() {
        assertEquals(1f - 1f / 10f, page(50, 0f).progress(10), 1e-6f) // clamps to the last page first
        assertEquals(0f, page(-5, 0f).progress(10), 0f)
        assertEquals(0f, page(3, 0.5f).progress(0), 0f)
    }

    @Test
    fun `reaching the end reads as 100 percent`() {
        assertEquals(1f, page(199, 0.2f).progress(200, atEnd = true), 0f)
        assertTrue(page(199, 0.2f).progress(200) < 1f)
    }

    @Test
    fun `a sample's progress uses its own unit count and end flag`() {
        assertEquals(0.25f, sample(page(25), count = 100).progress, 1e-6f)
        assertEquals(1f, sample(page(99), count = 100, atEnd = true).progress, 0f)
    }

    // ---- pixel conversions ----

    @Test
    fun `offset and fraction convert both ways`() {
        assertEquals(0.5f, fractionWithin(300, 600), 0f)
        assertEquals(300, offsetWithin(0.5f, 600))
        val size = 777
        for (offset in listOf(0, 1, 100, 388, 776)) {
            assertEquals(offset, offsetWithin(fractionWithin(offset, size), size))
        }
    }

    @Test
    fun `an item with no size yet converts to zero rather than dividing by it`() {
        assertEquals(0f, fractionWithin(50, 0), 0f)
        assertEquals(0f, fractionWithin(50, -1), 0f)
        assertEquals(0, offsetWithin(0.5f, 0))
    }

    @Test
    fun `a full fraction stays inside its own item`() {
        assertEquals(599, offsetWithin(1f, 600))
        assertEquals(0, offsetWithin(Float.NaN, 600))
        assertEquals(0, offsetWithin(-1f, 600))
    }

    @Test
    fun `a scrolled-past offset larger than the item reads as the item's end`() {
        assertEquals(1f, fractionWithin(900, 600), 0f)
    }

    // ---- same spot ----

    @Test
    fun `sub-percent rounding is the same spot, a different unit is not`() {
        assertTrue(page(4, 0.500f).isSameSpot(page(4, 0.505f)))
        assertFalse(page(4, 0.50f).isSameSpot(page(4, 0.52f)))
        assertFalse(page(4, 0f).isSameSpot(page(5, 0f)))
        assertFalse(page(4, 0f).isSameSpot(ReadingLocation(LocationKind.BLOCK, 4, 0f)))
    }

    @Test
    fun `a pinch alone, or reaching the end, is not the same spot`() {
        assertTrue(sample(page(4)).isSameSpot(sample(page(4), zoom = 1.005f)))
        assertFalse(sample(page(4)).isSameSpot(sample(page(4), zoom = 1.5f)))
        assertFalse(sample(page(4)).isSameSpot(sample(page(4), atEnd = true)))
    }

    // ---- stored rows ----

    private fun row(kind: Int, unit: Int = 3, fraction: Float = 0.5f, zoom: Float = 1.5f) =
        ReadingProgressEntity(1, kind, unit, fraction, 10, zoom, 0.35f, 0)

    @Test
    fun `a stored row restores for its own format`() {
        val restore = row(LocationKind.PAGE.code).toRestoreRequest(DocumentFormat.PDF)
        assertEquals(RestoreRequest(page(3, 0.5f), 1.5f), restore)
    }

    @Test
    fun `a stored row for another kind of content, or an unknown kind, restores nothing`() {
        assertNull(row(LocationKind.PAGE.code).toRestoreRequest(DocumentFormat.MARKDOWN))
        assertNull(row(77).toRestoreRequest(DocumentFormat.PDF))
        assertNull(row(LocationKind.PAGE.code).toRestoreRequest(DocumentFormat.UNKNOWN))
    }
}
