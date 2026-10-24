package com.papyrus.app.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which word an arrival lands on, and how its one match fades out. */
class ArrivalHighlightTest {

    @Test
    fun `the first searchable word is the one to land on`() {
        assertEquals("Scaling", focusTermFor("  Scaling   law", true))
    }

    @Test
    fun `the word keeps the case it was typed in`() {
        assertEquals("Papyrus", focusTermFor("Papyrus search", true))
    }

    @Test
    fun `a title-only match has no occurrence to land on`() {
        assertNull(focusTermFor("papyrus", false))
    }

    @Test
    fun `a query with no searchable word has nothing to land on`() {
        assertNull(focusTermFor("   ", true))
        assertNull(focusTermFor("!!!", true))
    }

    @Test
    fun `only the current match is painted during an arrival`() {
        val text = "needle needle needle"
        val spans = highlightedText(text, FindHighlight("needle", 0, onlyActive = true)).spanStyles
        assertEquals(1, spans.size)
        assertEquals(ActiveMatchColor, spans.single().item.background)
        assertEquals(0, spans.single().start)
        assertEquals(6, spans.single().end)
    }

    @Test
    fun `an out of range current match paints nothing`() {
        assertTrue(highlightedText("needle", FindHighlight("needle", 3, onlyActive = true)).spanStyles.isEmpty())
    }

    @Test
    fun `the background fades with alpha`() {
        val span = highlightedText("needle", FindHighlight("needle", 0, alpha = 0.5f)).spanStyles.single()
        assertEquals(ActiveMatchColor.copy(alpha = 0.5f), span.item.background)
    }

    @Test
    fun `the text returns to the document's colour as it fades`() {
        val base = Color(0xFF123456)
        val half = highlightedText("needle", FindHighlight("needle", 0, alpha = 0.5f), base).spanStyles.single()
        assertEquals(lerp(MatchTextColor, base, 0.5f), half.item.color)
        val gone = highlightedText("needle", FindHighlight("needle", 0, alpha = 0f), base).spanStyles.single()
        assertEquals(base, gone.item.color)
    }

    @Test
    fun `a full alpha draws exactly today's colours`() {
        val span = highlightedText("needle", FindHighlight("needle", 0)).spanStyles.single()
        assertEquals(ActiveMatchColor, span.item.background)
        assertEquals(MatchTextColor, span.item.color)
    }

    @Test
    fun `a term that is not in the text has no active range`() {
        assertNull(FindHighlight("absent", 0).activeRange("needle"))
    }

    @Test
    fun `skipping keeps the fade and the only-active rule`() {
        val skipped = FindHighlight("q", 3, alpha = 0.5f, onlyActive = true).skipping(3)
        assertEquals(0, skipped.activeOccurrence)
        assertEquals(0.5f, skipped.alpha)
        assertTrue(skipped.onlyActive)
    }
}
