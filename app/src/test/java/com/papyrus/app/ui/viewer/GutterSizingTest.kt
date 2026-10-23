package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gutter holds the longest number drawn and no more. */
class GutterSizingTest {

    @Test
    fun `a newline ending the file does not widen the column for the empty line it leaves`() {
        assertEquals(1, gutterDigits(10, endsWithNewline = true))
        assertEquals(2, gutterDigits(10, endsWithNewline = false))
        assertEquals(2, gutterDigits(100, endsWithNewline = true))
        assertEquals(3, gutterDigits(100, endsWithNewline = false))
    }

    @Test
    fun `an empty or single line file still holds one digit`() {
        assertEquals(1, gutterDigits(0, endsWithNewline = false))
        assertEquals(1, gutterDigits(1, endsWithNewline = false))
        assertEquals(1, gutterDigits(2, endsWithNewline = true))
    }

    @Test
    fun `the column always fits the last number drawn`() {
        for (lines in listOf(1, 9, 10, 99, 100, 999, 1000)) {
            val digits = gutterDigits(lines + 1, endsWithNewline = true)

            assertTrue("$lines needs ${lines.toString().length} digits, got $digits", digits >= lines.toString().length)
        }
    }
}
