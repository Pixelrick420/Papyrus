package com.papyrus.app.ui.viewer

import com.papyrus.app.ui.screens.FindState
import com.papyrus.app.viewer.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Find-in-file state transitions: query echo, stale-result rejection, hit highlighting. Split out of
 * ZoomAnchoringTest, which covers zoom anchoring and gesture arbitration.
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
    fun `a PDF search result carries the rectangles to highlight, grouped per match`() {
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val matches = mapOf(2 to listOf(listOf(rect)))
        val state = FindState(query = "ab")
            .withSearched("ab", hits = listOf(2), occurrences = 0, pageMatches = matches)
        assertEquals(matches, state.pageMatches)
    }

    @Test
    fun `rectangles for a stale query are discarded with the rest of its result`() {
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val state = FindState(query = "ab")
            .withSearched("a", hits = listOf(2), occurrences = 0, pageMatches = mapOf(2 to listOf(listOf(rect))))
        assertTrue(state.pageMatches.isEmpty())
    }

    @Test
    fun `echoing a keystroke leaves the previous rectangles on screen until the search lands`() {
        // Highlights stay until the search lands, or they flicker on every keystroke.
        val rect = NormRect(0.1f, 0.2f, 0.3f, 0.25f)
        val matches = mapOf(2 to listOf(listOf(rect)))
        val state = FindState(query = "a", hits = listOf(2), pageMatches = matches)
        assertEquals(matches, state.withQueryEchoed("ab").pageMatches)
    }

    // Hits hold one entry per match, so a container with several matches repeats. These cover how the
    // cursor over that list becomes "the second match in that container".

    @Test
    fun `each match is its own step, so the count is the number of matches`() {
        // Regression: three matches in chunk 0 used to be one hit, so the counter read 1 of 1.
        val state = FindState(query = "x", hits = listOf(0, 0, 0, 2, 2), position = 0)
        assertEquals(5, state.count)
    }

    @Test
    fun `the active occurrence counts matches within the active container`() {
        val hits = listOf(0, 0, 0, 2, 2, 5)
        val occurrences = hits.indices.map { FindState(hits = hits, position = it).activeOccurrence }
        assertEquals(listOf(0, 1, 2, 0, 1, 0), occurrences)
    }

    @Test
    fun `the container is the same for every match inside it`() {
        val hits = listOf(4, 4, 4, 9)
        assertEquals(listOf(4, 4, 4, 9), hits.indices.map { FindState(hits = hits, position = it).activeIndex })
    }

    @Test
    fun `there is no active occurrence when there is no active hit`() {
        assertNull(FindState().activeOccurrence)
        assertNull(FindState(hits = listOf(1, 1), position = -1).activeOccurrence)
        assertNull(FindState(hits = listOf(1, 1), position = 2).activeOccurrence)
        assertNull(FindState(hits = emptyList(), position = 0).activeOccurrence)
    }

    @Test
    fun `a search lands on the first match of the first container`() {
        val state = FindState(query = "ab").withSearched("ab", hits = listOf(3, 3, 7), occurrences = 0)
        assertEquals(3, state.activeIndex)
        assertEquals(0, state.activeOccurrence)
    }

    @Test
    fun `stepping through a cluster highlights one match at a time and then moves on`() {
        // The reported bug: matches close together lit up as one. Walking the cursor must visit each
        // match of the cluster individually before reaching the next container.
        val hits = listOf(1, 1, 1, 6)
        val walk = hits.indices.map { FindState(hits = hits, position = it) }.map { it.activeIndex to it.activeOccurrence }
        assertEquals(listOf(1 to 0, 1 to 1, 1 to 2, 6 to 0), walk)
    }

    @Test
    fun `the first container of a long hit list resolves without scanning`() {
        // The lookup is a binary search; a long list must still give the right answer at both ends.
        val hits = List(50_000) { it / 1000 }
        assertEquals(0, FindState(hits = hits, position = 0).activeOccurrence)
        assertEquals(999, FindState(hits = hits, position = 999).activeOccurrence)
        assertEquals(0, FindState(hits = hits, position = 1000).activeOccurrence)
        assertEquals(999, FindState(hits = hits, position = hits.lastIndex).activeOccurrence)
    }
}
