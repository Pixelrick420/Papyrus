package io.github.pixelrick420.papyrus.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficePaperFillTest {

    @Test
    fun `white and near-white neutral fills are paper`() {
        assertTrue(isPaperWhite(0xFFFFFF))
        assertTrue(isPaperWhite(0xF8F8F8))
        assertTrue(isPaperWhite(0xF0F0F0))
    }

    @Test
    fun `a tinted fill is a choice and is kept`() {
        assertFalse(isPaperWhite(0xD9E2F3))
        assertFalse(isPaperWhite(0xFFFF00))
        assertFalse(isPaperWhite(0xFFFDE0))
        assertFalse(isPaperWhite(0xEDEDED))
    }
}
