package com.papyrus.app.data

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The indexer's queue behaviour: what it reads, in what order it extracts, and what it writes.
 *
 * The database is the queue by design, so these tests are about what the indexer does with one
 * answer: a batch is drained until it comes back empty, a document removed while it was being read
 * is skipped, and a failed extraction is written down so the queue cannot stall on it.
 */
class SearchIndexerTest {

    private val dao = FakeSearchDao()
    private val extracted = mutableListOf<Long>()

    private fun document(id: Long, title: String = "Document $id", size: Long = 1_000) = DocumentEntity(
        id = id,
        uri = "content://test/$id",
        title = title,
        format = DocumentFormat.PDF,
        sizeBytes = size,
        createdAt = 0,
        lastOpenedAt = 0,
    )

    /** Text for every document, remembering the order extraction was asked in. */
    private fun extraction(text: (DocumentEntity) -> String = { "text of ${it.title}" }) =
        SearchIndexer(
            dao = dao,
            extract = { doc ->
                extracted += doc.id
                ExtractedText(text(doc), TextIndexState.INDEXED)
            },
            library = emptyFlow<Unit>(),
        )

    @Test
    fun `every pending document is written with its extracted text`() {
        dao.queue += listOf(document(1, "One"), document(2, "Two"))

        runBlocking { extraction().indexPending() }

        assertEquals(listOf(1L, 2L), extracted)
        assertEquals(listOf(1L, 2L), dao.written.map { it.documentId })
        assertEquals(listOf("text of One", "text of Two"), dao.written.map { it.text })
    }

    @Test
    fun `the written row records the length, state, source size and index version`() {
        dao.queue += document(1, size = 4_096)

        runBlocking { extraction().indexPending() }

        val row = dao.written.single()
        assertEquals("text of Document 1", row.text)
        assertEquals(row.text.length, row.charCount)
        assertEquals(TextIndexState.INDEXED, row.state)
        assertEquals(4_096L, row.sourceSize)
        assertEquals(SearchIndexer.INDEX_VERSION, row.indexVersion)
        assertTrue("indexedAt should be a real timestamp", row.indexedAt > 0)
    }

    @Test
    fun `the queue is drained in batches smaller than a large library`() {
        dao.queue += (1L..20L).map { document(it) }

        runBlocking { extraction().indexPending() }

        assertEquals(20, dao.written.size)
        // Two batches and the empty read that ends the pass: 16, 4, then nothing left.
        assertEquals(3, dao.pendingQueries.size)
        assertEquals(SearchIndexer.INDEX_VERSION, dao.pendingQueries.first().first)
        assertTrue("a batch should be bounded", dao.pendingQueries.first().second < 20)
    }

    @Test
    fun `a document removed while its text was being read is not indexed`() {
        dao.queue += listOf(document(1), document(2))
        dao.existingIds = setOf(2)

        runBlocking { extraction().indexPending() }

        assertEquals(listOf(1L, 2L), extracted)
        assertEquals(listOf(2L), dao.written.map { it.documentId })
    }

    @Test
    fun `an extraction that failed is written down so the queue moves past it`() {
        // Terminal on purpose: retrying a corrupt file on every pass would stall the whole queue.
        val indexer = SearchIndexer(
            dao = dao,
            extract = { ExtractedText("", TextIndexState.FAILED) },
            library = emptyFlow<Unit>(),
        )
        dao.queue += document(1)

        runBlocking { indexer.indexPending() }

        val row = dao.written.single()
        assertEquals(TextIndexState.FAILED, row.state)
        assertEquals("", row.text)
        assertEquals(0, row.charCount)
    }

    @Test
    fun `an encrypted document is written down as skipped`() {
        val indexer = SearchIndexer(
            dao = dao,
            extract = { ExtractedText("", TextIndexState.SKIPPED) },
            library = emptyFlow<Unit>(),
        )
        dao.queue += document(1)

        runBlocking { indexer.indexPending() }

        assertEquals(TextIndexState.SKIPPED, dao.written.single().state)
    }

    @Test
    fun `replacing a row deletes it first, so the old text leaves the index`() {
        // The FTS delete trigger only fires on a real DELETE; a REPLACE would leave stale words behind.
        dao.queue += document(1)

        runBlocking { extraction().indexPending() }

        assertEquals(listOf(1L), dao.deleted)
        assertEquals(listOf(1L), dao.written.map { it.documentId })
    }

    @Test
    fun `an empty queue does nothing`() {
        runBlocking { extraction().indexPending() }

        assertEquals(1, dao.pendingQueries.size)
        assertTrue(dao.written.isEmpty())
        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `a library change runs another pass, so an import is indexed`() {
        val indexer = SearchIndexer(
            dao = dao,
            extract = { ExtractedText("indexed", TextIndexState.INDEXED) },
            library = flowOf(Unit),
        )

        runBlocking { indexer.start(this) }

        // One pass for the start-up call and one for the emission; each starts with an empty read.
        assertTrue("the change should have been acted on", dao.pendingQueries.size >= 2)
    }

    @Test
    fun `starting twice runs one pass, not two`() = runBlocking {
        val indexer = extraction()

        indexer.start(this)
        indexer.start(this)
        repeat(20) { yield() }

        assertEquals(1, dao.pendingQueries.size)
    }
}
