package com.papyrus.app.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A white paper fill is dropped so a table cell keeps the app's surface; a tinted fill is kept. */
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
