package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which byte offset `offsets()` output points at. The format is space-separated quadruples of
 * (column, term index, byte offset, byte length), so reading the wrong slot silently returns the
 * column number or the term index instead of a position in the text.
 */
class FtsOffsetsTest {

    @Test
    fun `the earliest occurrence is the one kept`() {
        assertEquals(15, FtsOffsets.earliest("0 0 15 5 0 0 40 5"))
    }

    @Test
    fun `a single occurrence is read from its own quadruple`() {
        assertEquals(7, FtsOffsets.earliest("0 0 7 3"))
    }

    @Test
    fun `the slot read is the byte offset, not the column or the term index`() {
        // Column 2 would read as 2, term index 1 as 1; the offset is 500.
        assertEquals(500, FtsOffsets.earliest("2 1 500 6"))
    }

    @Test
    fun `occurrences in any order still yield the earliest one`() {
        assertEquals(3, FtsOffsets.earliest("0 1 90 4 0 0 3 2 1 0 55 9"))
    }

    @Test
    fun `one occurrence per term is enough to pick the earliest`() {
        // Three phrases, as a three-word query produces.
        assertEquals(12, FtsOffsets.earliest("0 0 40 5 0 1 12 4 0 2 77 6"))
    }

    @Test
    fun `a byte offset of zero is a position, not a missing value`() {
        assertEquals(0, FtsOffsets.earliest("0 0 0 5"))
    }

    @Test
    fun `a null or blank answer means nothing matched`() {
        assertNull(FtsOffsets.earliest(null))
        assertNull(FtsOffsets.earliest(""))
        assertNull(FtsOffsets.earliest("   "))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(9, FtsOffsets.earliest(" 0 0 9 4 "))
    }
}
