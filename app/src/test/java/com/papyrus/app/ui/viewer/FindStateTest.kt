package com.papyrus.app.ui.viewer

import com.papyrus.app.ui.screens.FindState
import com.papyrus.app.viewer.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Find-in-file state transitions: query echo, stale-result rejection and hit highlighting.
 * Split out of ZoomAnchoringTest, which covers zoom anchoring and gesture arbitration.
 */
class FindStateTest {

    @Test
    fun `a keystroke is echoed immediately`() {
        // Regression: the query was written inside the debounced search, so the field looked dead.
        val state = FindState()
        assertEquals("a", state.withQueryEchoed("a").query)
        assertEquals("ab", state.withQueryEchoed("a").withQueryEchoed("ab").query)
    }

    @Test
    fun `echoing a new query invalidates the cursor but leaves the hits visible`() {
        // Hits stay so the counter still describes the last searched query; the cursor cannot.
        val state = FindState(query = "a", hits = listOf(3, 7, 9), position = 2)
        val echoed = state.withQueryEchoed("ab")

        assertEquals(listOf(3, 7, 9), echoed.hits)
        assertEquals(-1, echoed.position)
        assertNull(echoed.activeIndex)
    }

    @Test
    fun `re-echoing the same query keeps the cursor where it was`() {
        val state = FindState(query = "ab", hits = listOf(1, 2), position = 1)
        val echoed = state.withQueryEchoed("ab")
        assertEquals(1, echoed.position)
        assertEquals(2, echoed.activeIndex)
    }

    @Test
    fun `a search result for a stale query is discarded`() {
        // Cancellation is a request, not a guarantee: an in-flight search still delivers.
        val state = FindState(query = "ab").withSearched("a", hits = listOf(1, 2, 3), occurrences = 3)
        assertEquals(emptyList<Int>(), state.hits)
        assertEquals(0, state.occurrences)
    }

    @Test
    fun `a search result for the current query is applied and starts at the first hit`() {
        val state = FindState(query = "ab").withSearched("ab", hits = listOf(4, 8), occurrences = 2)
        assertEquals(listOf(4, 8), state.hits)
        assertEquals(0, state.position)
        assertEquals(4, state.activeIndex)
    }

    @Test
    fun `a search with no hits leaves no active index rather than pointing at the first`() {
        val state = FindState(query = "zz", hits = listOf(1), position = 0)
            .withSearched("zz", hits = emptyList(), occurrences = 0)
        assertEquals(-1, state.position)
        assertNull(state.activeIndex)
        assertFalse(state.navigable)
    }

    @Test
    fun `a PDF search result carries the rectangles to highlight`() {
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val state = FindState(query = "ab")
            .withSearched("ab", hits = listOf(2), occurrences = 0, pageRects = mapOf(2 to listOf(rect)))
        assertEquals(mapOf(2 to listOf(rect)), state.pageRects)
    }

    @Test
    fun `rectangles for a stale query are discarded with the rest of its result`() {
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val state = FindState(query = "ab")
            .withSearched("a", hits = listOf(2), occurrences = 0, pageRects = mapOf(2 to listOf(rect)))
        assertTrue(state.pageRects.isEmpty())
    }

    @Test
    fun `echoing a keystroke leaves the previous rectangles on screen until the search lands`() {
        // Highlights stay until the search lands, or they flicker on every keystroke.
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val state = FindState(query = "a", hits = listOf(2), pageRects = mapOf(2 to listOf(rect)))
        assertEquals(mapOf(2 to listOf(rect)), state.withQueryEchoed("ab").pageRects)
    }
}
