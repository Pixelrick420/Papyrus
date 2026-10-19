package com.papyrus.app.ui.screens

import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list's states: what the query hides, what removal hides, and what the empty text refers to. */
class HomeUiStateTest {

    private fun doc(id: Long, title: String) = DocumentEntity(
        id = id,
        uri = "content://test/$id",
        title = title,
        format = DocumentFormat.PDF,
        sizeBytes = 0,
        createdAt = 0,
        lastOpenedAt = 0,
    )

    private val report = doc(1, "Report.pdf")
    private val notes = doc(2, "meeting notes.txt")
    private val library = listOf(report, notes)

    /** Every builder input, so each test only names the one it varies. */
    private fun homeState(
        all: List<DocumentEntity> = library,
        query: String = "",
        selection: SelectionState = SelectionState(),
        pendingRemovalToken: Long? = null,
        hidden: Set<Long> = emptySet(),
    ) = buildHomeUiState(all, query, selection, pendingRemovalToken, hidden)

    @Test
    fun `a blank query keeps the whole library`() {
        val state = homeState()

        assertEquals(library, state.documents)
        assertEquals(library, state.results)
        assertEquals("", state.query)
        assertTrue(state.loaded)
    }

    @Test
    fun `the query matches titles case-insensitively and is trimmed for the empty text`() {
        val state = homeState(query = "  REPORT  ")

        assertEquals("REPORT", state.query)
        assertEquals(listOf(report), state.results)
    }

    @Test
    fun `documents keeps the unfiltered library so bulk actions reach rows the query hides`() {
        val state = homeState(query = "report")

        assertEquals(library, state.documents)
        assertEquals(listOf(report), state.results)
    }

    @Test
    fun `a query matching nothing leaves the library but no results`() {
        val state = homeState(query = "zzz")

        assertEquals(library, state.documents)
        assertTrue(state.results.isEmpty())
        assertFalse(state.libraryEmpty)
    }

    @Test
    fun `a hidden id is left out of both the library and the results`() {
        val state = homeState(hidden = setOf(notes.id))

        assertEquals(listOf(report), state.documents)
        assertEquals(listOf(report), state.results)
    }

    @Test
    fun `hiding every row reads as an empty library, not an empty search`() {
        // The distinction matters: the overlay shows the first-run message only when libraryEmpty holds.
        val state = homeState(all = listOf(report), hidden = setOf(report.id))

        assertTrue(state.libraryEmpty)
        assertTrue(state.documents.isEmpty())
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `a library with no rows reads as empty and loaded`() {
        val state = homeState(all = emptyList())

        assertTrue(state.libraryEmpty)
        assertTrue(state.loaded)
    }

    @Test
    fun `the selection and the pending removal token are carried through unchanged`() {
        val selection = SelectionState(active = true, ids = setOf(report.id))

        val state = homeState(selection = selection, pendingRemovalToken = 42L)

        assertEquals(selection, state.selection)
        assertEquals(42L, state.pendingRemovalToken)
    }

    @Test
    fun `no pending removal leaves the token null`() {
        assertNull(homeState().pendingRemovalToken)
    }
}
