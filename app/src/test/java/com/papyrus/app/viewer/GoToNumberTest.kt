package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoToNumberTest {

    @Test
    fun `a number inside the range is returned`() {
        assertEquals(1, parseGoToNumber("1", 200))
        assertEquals(200, parseGoToNumber("200", 200))
        assertEquals(42, parseGoToNumber("42", 200))
    }

    @Test
    fun `surrounding spaces are forgiven`() {
        assertEquals(7, parseGoToNumber("  7 ", 10))
    }

    @Test
    fun `zero, past the end and negative are out of range`() {
        assertNull(parseGoToNumber("0", 200))
        assertNull(parseGoToNumber("201", 200))
        assertNull(parseGoToNumber("-3", 200))
    }

    @Test
    fun `anything that is not plain digits is rejected`() {
        for (bad in listOf("", "   ", "abc", "1a", "1.5", "1,000", "+5", "١٢", "5 6")) {
            assertNull("'$bad'", parseGoToNumber(bad, 1000))
        }
    }

    @Test
    fun `an absurdly long number is just out of range`() {
        assertNull(parseGoToNumber("9".repeat(40), 200))
    }

    @Test
    fun `a document with no pages accepts nothing`() {
        assertNull(parseGoToNumber("1", 0))
        assertNull(parseGoToNumber("1", -5))
    }
}
