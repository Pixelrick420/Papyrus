package com.papyrus.app.ui.screens

import com.papyrus.app.data.ContentHit
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.FormatGroup
import com.papyrus.app.data.LibraryFilter
import com.papyrus.app.data.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list's states: what the query hides, what removal hides, and what the empty text refers to. */
class HomeUiStateTest {

    private fun doc(id: Long, title: String, format: DocumentFormat = DocumentFormat.PDF) = DocumentEntity(
        id = id,
        uri = "content://test/$id",
        title = title,
        format = format,
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
        filter: LibraryFilter = LibraryFilter(),
        selection: SelectionState = SelectionState(),
        pendingRemovalToken: Long? = null,
        hidden: Set<Long> = emptySet(),
        contentHits: Map<Long, ContentHit> = emptyMap(),
        searching: Boolean = false,
    ) = buildHomeUiState(
        all,
        ViewParams(query, filter, LibrarySort.LAST_OPENED),
        selection,
        pendingRemovalToken,
        hidden,
        contentHits,
        searching,
    )

    private fun hit(documentId: Long, snippet: String = "the paper trail") = ContentHit(
        documentId = documentId,
        snippet = snippet,
        highlightRanges = listOf(4..8),
        score = 1.0,
    )

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

    @Test
    fun `csv and tsv are two groups in the sheet`() {
        val state = homeState(
            all = listOf(
                doc(1, "Report.pdf"),
                doc(2, "table.csv", DocumentFormat.CSV),
                doc(3, "table.tsv", DocumentFormat.CSV),
            ),
        )

        assertEquals(listOf(FormatGroup.PDF, FormatGroup.CSV, FormatGroup.TSV), state.groups)
    }

    @Test
    fun `a selected group stays in the list so it can be switched off again`() {
        val state = homeState(filter = LibraryFilter().toggled(FormatGroup.EPUB))

        assertEquals(listOf(FormatGroup.PDF, FormatGroup.EPUB), state.groups)
    }

    @Test
    fun `a document whose text matched is listed even though its title did not`() {
        // "meeting notes.txt" holds the word; its title does not, so only the content hit keeps it.
        val state = homeState(query = "paper", contentHits = mapOf(notes.id to hit(notes.id)))

        assertEquals(listOf(notes), state.results)
        assertEquals(notes.id, state.contentHits.getValue(notes.id).documentId)
    }

    @Test
    fun `the excerpt of a title match is shown too, when the text matched as well`() {
        val state = homeState(query = "report", contentHits = mapOf(report.id to hit(report.id)))

        assertEquals(listOf(report), state.results)
        assertEquals(listOf(4..8), state.contentHits.getValue(report.id).highlightRanges)
    }

    @Test
    fun `a content hit the filter hides does not come back`() {
        // The chips narrow what a search can surface, exactly as they narrow the browsing list.
        val notes = doc(2, "meeting notes.txt", DocumentFormat.TEXT)
        val pdfOnly = LibraryFilter().toggled(FormatGroup.PDF)

        val state = homeState(
            all = listOf(report, notes),
            query = "paper",
            filter = pdfOnly,
            contentHits = mapOf(notes.id to hit(notes.id)),
        )

        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `a content hit for a hidden row does not come back either`() {
        val state = homeState(query = "paper", hidden = setOf(notes.id), contentHits = mapOf(notes.id to hit(notes.id)))

        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `a running content search is carried through, so an empty list can say it is still looking`() {
        val state = homeState(query = "zzz", searching = true)

        assertTrue(state.searching)
        assertTrue(state.results.isEmpty())
        assertFalse(state.libraryEmpty)
    }

    @Test
    fun `no content search leaves no excerpts and is not searching`() {
        val state = homeState(query = "report")

        assertTrue(state.contentHits.isEmpty())
        assertFalse(state.searching)
    }
}
