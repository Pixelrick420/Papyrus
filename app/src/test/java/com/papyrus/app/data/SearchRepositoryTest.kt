package com.papyrus.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-phase query's call plumbing and its answer: the order of the hits, which candidates are
 * dropped, and what the excerpt looks like once FTS4's markers are gone.
 *
 * The SQL itself is not exercised here — [FakeSearchDao] stands in for it — so this covers exactly
 * what the repository adds on top: ranking, limiting, marker stripping and the offset it carries.
 */
class SearchRepositoryTest {

    private val dao = FakeSearchDao()
    private val repository = SearchRepository(dao)

    /** One `matchinfo` blob scoring [score]: p=1, c=1, n=100, a=100, l=100, x=[1, 100, df]. */
    private fun matchInfo(documentFrequency: Int): ByteArray {
        val ints = intArrayOf(1, 1, 100, 100, 100, 1, 100, documentFrequency)
        return ints.flatMap { value -> (0 until 4).map { shift -> (value shr (8 * shift)).toByte() } }
            .toByteArray()
    }

    private fun snippet(documentId: Long, text: String, offsets: String? = "0 0 0 4") =
        SnippetRow(documentId = documentId, snippet = text, termOffsets = offsets)

    @Test
    fun `the best-ranked document comes first`() {
        // A rarer word scores higher, so document 2 outranks document 1.
        dao.candidateRows = listOf(
            RawContentMatch(1, matchInfo(documentFrequency = 50)),
            RawContentMatch(2, matchInfo(documentFrequency = 1)),
        )
        dao.snippetRows = listOf(snippet(1, "one"), snippet(2, "two"))

        val hits = runBlocking { repository.search("paper") }

        assertEquals(listOf(2L, 1L), hits.map { it.documentId })
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `equal scores fall back to the document id, so the order is stable`() {
        val same = matchInfo(documentFrequency = 1)
        dao.candidateRows = listOf(RawContentMatch(9, same), RawContentMatch(3, same), RawContentMatch(5, same))
        dao.snippetRows = listOf(snippet(9, "nine"), snippet(3, "three"), snippet(5, "five"))

        val hits = runBlocking { repository.search("paper") }

        assertEquals(listOf(3L, 5L, 9L), hits.map { it.documentId })
    }

    @Test
    fun `at most the limit is returned, best first`() {
        dao.candidateRows = listOf(
            RawContentMatch(1, matchInfo(documentFrequency = 80)),
            RawContentMatch(2, matchInfo(documentFrequency = 40)),
            RawContentMatch(3, matchInfo(documentFrequency = 1)),
        )
        dao.snippetRows = listOf(snippet(1, "one"), snippet(2, "two"), snippet(3, "three"))

        val hits = runBlocking { repository.search("paper", limit = 2) }

        assertEquals(listOf(3L, 2L), hits.map { it.documentId })
    }

    @Test
    fun `excerpts are requested for the ranked ids only, best first`() {
        dao.candidateRows = listOf(
            RawContentMatch(1, matchInfo(documentFrequency = 80)),
            RawContentMatch(2, matchInfo(documentFrequency = 1)),
        )
        dao.snippetRows = listOf(snippet(1, "one"), snippet(2, "two"))

        runBlocking { repository.search("paper", limit = 1) }

        assertEquals(listOf("\"paper*\"" to listOf(2L)), dao.snippetQueries)
    }

    @Test
    fun `a candidate with no excerpt is dropped rather than shown without a reason`() {
        dao.candidateRows = listOf(
            RawContentMatch(1, matchInfo(documentFrequency = 1)),
            RawContentMatch(2, matchInfo(documentFrequency = 50)),
        )
        dao.snippetRows = listOf(snippet(1, "one"))

        val hits = runBlocking { repository.search("paper") }

        assertEquals(listOf(1L), hits.map { it.documentId })
    }

    @Test
    fun `the reader's text reaches the index as a quoted prefix expression`() {
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(snippet(1, "scaling"))

        runBlocking { repository.search("  scal  ") }

        assertEquals(listOf("\"scal*\""), dao.candidateQueries)
    }

    @Test
    fun `nothing searchable means no query at all`() {
        val hits = runBlocking { repository.search("  ?!  ") }

        assertTrue(hits.isEmpty())
        assertTrue(dao.candidateQueries.isEmpty())
        assertTrue(dao.snippetQueries.isEmpty())
    }

    @Test
    fun `an empty match list means no excerpt request`() {
        val hits = runBlocking { repository.search("paper") }

        assertTrue(hits.isEmpty())
        assertTrue(dao.snippetQueries.isEmpty())
    }

    @Test
    fun `the markers around matches are removed and become highlight ranges`() {
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(snippet(1, "the \u0001scale\u0002 of \u0001paper\u0002"))

        val hit = runBlocking { repository.search("paper") }.single()

        assertEquals("the scale of paper", hit.snippet)
        assertEquals(listOf(4..8, 13..17), hit.highlightRanges)
    }

    @Test
    fun `an unterminated marker highlights the rest of the excerpt`() {
        // A snippet cut at its length limit can end inside a match.
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(snippet(1, "the \u0001scal"))

        val hit = runBlocking { repository.search("paper") }.single()

        assertEquals("the scal", hit.snippet)
        assertEquals(listOf(4..7), hit.highlightRanges)
    }

    @Test
    fun `adjacent markers produce no empty range`() {
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(snippet(1, "\u0001\u0002plain"))

        val hit = runBlocking { repository.search("paper") }.single()

        assertEquals("plain", hit.snippet)
        assertTrue(hit.highlightRanges.isEmpty())
    }

    @Test
    fun `a null excerpt reads as empty text, not as a missing hit`() {
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(SnippetRow(documentId = 1, snippet = null, termOffsets = null))

        val hit = runBlocking { repository.search("paper") }.single()

        assertEquals("", hit.snippet)
        assertTrue(hit.highlightRanges.isEmpty())
        assertNull(hit.matchOffset)
    }

    @Test
    fun `the earliest match's byte offset is carried for opening at the match`() {
        dao.candidateRows = listOf(RawContentMatch(1, matchInfo(documentFrequency = 1)))
        dao.snippetRows = listOf(snippet(1, "the \u0001paper\u0002", offsets = "0 0 42 5 0 0 7 5"))

        val hit = runBlocking { repository.search("paper") }.single()

        assertEquals(7, hit.matchOffset)
    }
}
