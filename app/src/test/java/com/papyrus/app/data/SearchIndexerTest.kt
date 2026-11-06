package com.papyrus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
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

    // --- Only what is new or changed ---

    /** An indexed document and the modified time its text was read at. */
    private fun indexedWith(id: Long, modified: Long) = IndexedDocument(document(id), modified)

    @Test
    fun `an unchanged document is not read again`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500), indexedWith(2, modified = 700))
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc -> extracted += doc.id; ExtractedText("again", TextIndexState.INDEXED) },
            library = emptyFlow<Unit>(),
            lastModified = { doc -> if (doc.id == 1L) 500 else 700 },
        )

        runBlocking { indexer.indexChanged() }

        assertTrue("nothing changed, so nothing should be extracted", extracted.isEmpty())
        assertTrue(dao.written.isEmpty())
    }

    @Test
    fun `a document whose modified time moved is read again and the new time is stored`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500), indexedWith(2, modified = 700))
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc -> extracted += doc.id; ExtractedText("new text", TextIndexState.INDEXED) },
            library = emptyFlow<Unit>(),
            lastModified = { doc -> if (doc.id == 1L) 500 else 900 },
        )

        runBlocking { indexer.indexChanged() }

        assertEquals(listOf(2L), extracted)
        val row = dao.written.single()
        assertEquals(2L, row.documentId)
        assertEquals(900L, row.sourceModified)
        assertEquals("new text", row.text)
    }

    @Test
    fun `a provider with nothing to say about a document leaves it alone`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500), indexedWith(2, modified = 700))
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc -> extracted += doc.id; ExtractedText("x", TextIndexState.INDEXED) },
            library = emptyFlow<Unit>(),
            // No answer for one, and zero, which a provider uses for "unknown", for the other.
            lastModified = { doc -> if (doc.id == 1L) null else 0 },
        )

        runBlocking { indexer.indexChanged() }

        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `the check pages through a library larger than a batch`() {
        dao.indexedRows = (1L..20L).map { indexedWith(it, modified = 1) }
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc -> extracted += doc.id; ExtractedText("x", TextIndexState.INDEXED) },
            library = emptyFlow<Unit>(),
            lastModified = { 2 },
        )

        runBlocking { indexer.indexChanged() }

        assertEquals((1L..20L).toList(), extracted)
        // 16, 4, then the empty read that ends it.
        assertEquals(3, dao.indexedQueries)
    }

    @Test
    fun `the time recorded is the one the provider gave before the file was opened`() {
        // An edit made while the file is being read must leave the stored time behind the file's, so the
        // next check sees it instead of recording the new time against the old text.
        var asked = 0
        val indexer = SearchIndexer(
            dao = dao,
            extract = { ExtractedText("text", TextIndexState.INDEXED) },
            library = emptyFlow<Unit>(),
            lastModified = { asked++; 100L + asked },
        )
        dao.queue += document(1)

        runBlocking { indexer.indexPending() }

        assertEquals(101L, dao.written.single().sourceModified)
    }

    @Test
    fun `a provider that gives no time is stored as not recorded`() {
        dao.queue += document(1)

        runBlocking { extraction().indexPending() }

        assertEquals(0L, dao.written.single().sourceModified)
    }

    @Test
    fun `the library is checked for edits once, not on every pass`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500))
        val indexer = SearchIndexer(
            dao = dao,
            extract = { ExtractedText("x", TextIndexState.INDEXED) },
            library = flowOf(Unit, Unit),
            lastModified = { 500 },
        )

        // Finishes by itself: the library flow is finite, and each change settles for a second first.
        runBlocking { indexer.start(this) }

        // The start-up pass and one for each change were all asked for the queue...
        assertTrue("a library change should still run a pass", dao.pendingQueries.size >= 3)
        // ...but only the first walked the indexed rows: a full page, then the empty read that ends it.
        assertEquals(2, dao.indexedQueries)
    }

    // --- Only while it may ---

    /** Runs [body] while [indexer] is started on this scope, then stops whatever it left running. */
    private fun whileIndexing(indexer: SearchIndexer, body: suspend CoroutineScope.() -> Unit) = runBlocking {
        indexer.start(this)
        try {
            body()
        } finally {
            coroutineContext.job.cancelChildren()
        }
    }

    private fun gatedIndexer(
        open: MutableStateFlow<Boolean>,
        startDelayMillis: Long = 0,
        lastModified: suspend (DocumentEntity) -> Long? = { null },
        // Off by default so these tests exercise the gate, not the small-file exception.
        eagerMaxBytes: Long = 0,
    ) = SearchIndexer(
        dao = dao,
        extract = { doc -> extracted += doc.id; ExtractedText("text", TextIndexState.INDEXED) },
        library = emptyFlow<Unit>(),
        lastModified = lastModified,
        mayIndex = open,
        startDelayMillis = startDelayMillis,
        eagerMaxBytes = eagerMaxBytes,
    )

    @Test
    fun `nothing is read while indexing is not allowed`() {
        dao.queue += listOf(document(1), document(2))

        whileIndexing(gatedIndexer(MutableStateFlow(false))) {
            repeat(20) { yield() }
            delay(50)
        }

        assertTrue("no document should have been opened", extracted.isEmpty())
        assertTrue("not even the queue should have been read", dao.pendingQueries.isEmpty())
    }

    @Test
    fun `indexing starts when it becomes allowed`() {
        dao.queue += listOf(document(1), document(2))
        val open = MutableStateFlow(false)

        whileIndexing(gatedIndexer(open)) {
            repeat(20) { yield() }
            assertTrue(extracted.isEmpty())

            open.value = true
            delay(100)
        }

        assertEquals(listOf(1L, 2L), extracted)
    }

    @Test
    fun `a pass stops between documents when indexing stops being allowed, and resumes where it was`() {
        dao.queueUntilWritten = true
        dao.queue += listOf(document(1), document(2), document(3))
        val open = MutableStateFlow(true)
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc ->
                extracted += doc.id
                // The reader opens a document while the first one is being read.
                if (doc.id == 1L) open.value = false
                ExtractedText("text", TextIndexState.INDEXED)
            },
            library = emptyFlow<Unit>(),
            mayIndex = open,
            eagerMaxBytes = 0,
        )

        whileIndexing(indexer) {
            delay(100)
            // The one in flight is finished and written; the next is not started.
            assertEquals(listOf(1L), extracted)
            assertEquals(listOf(1L), dao.written.map { it.documentId })

            open.value = true
            delay(100)
        }

        assertEquals(listOf(1L, 2L, 3L), extracted)
    }

    @Test
    fun `the first pass waits out the start delay`() {
        dao.queue += document(1)

        whileIndexing(gatedIndexer(MutableStateFlow(true), startDelayMillis = 300)) {
            delay(100)
            assertTrue("started before the delay was over", extracted.isEmpty())
            delay(400)
        }

        assertEquals(listOf(1L), extracted)
    }

    @Test
    fun `a small file is indexed while indexing is not allowed`() {
        dao.queue +=
            listOf(document(1, size = 4_096), document(2, size = SearchIndexer.EAGER_MAX_BYTES + 1))

        whileIndexing(
            gatedIndexer(
                MutableStateFlow(false),
                startDelayMillis = 300,
                eagerMaxBytes = SearchIndexer.EAGER_MAX_BYTES,
            ),
        ) {
            repeat(20) { yield() }
            delay(50)
            assertEquals("the small file should not wait for the gate or the delay", listOf(1L), extracted)
        }

        assertEquals(listOf(1L), dao.written.map { it.documentId })
        assertTrue("the large file still waits for the gate", dao.pendingQueries.isEmpty())
    }

    @Test
    fun `the eager pass asks only for files up to its limit`() = runBlocking {
        dao.queue +=
            listOf(document(1, size = 1_000), document(2, size = SearchIndexer.EAGER_MAX_BYTES + 1))

        extraction().indexEager()

        assertEquals(listOf(1L), extracted)
        val (maxBytes, limit) = dao.eagerPendingQueries.first()
        assertEquals(SearchIndexer.EAGER_MAX_BYTES, maxBytes)
        assertTrue("the read should be bounded", limit < 20)
    }

    @Test
    fun `pausing and resuming does not walk the indexed rows again`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500))
        val open = MutableStateFlow(true)

        whileIndexing(gatedIndexer(open, lastModified = { 500 })) {
            delay(100)
            // A full walk: the one page, then the empty read that ends it.
            assertEquals(2, dao.indexedQueries)

            open.value = false
            delay(50)
            open.value = true
            delay(100)
        }

        assertEquals("the edit check is once per process, not once per pause", 2, dao.indexedQueries)
    }

    @Test
    fun `an edit check that was paused part way is finished when indexing resumes`() {
        dao.indexedRows = listOf(indexedWith(1, modified = 500), indexedWith(2, modified = 500))
        val open = MutableStateFlow(true)
        val indexer = SearchIndexer(
            dao = dao,
            extract = { doc ->
                extracted += doc.id
                // The reader opens a document while the first changed one is being read.
                if (doc.id == 1L && extracted.size == 1) open.value = false
                ExtractedText("text", TextIndexState.INDEXED)
            },
            library = emptyFlow<Unit>(),
            lastModified = { 900 },
            mayIndex = open,
            eagerMaxBytes = 0,
        )

        whileIndexing(indexer) {
            delay(100)
            assertEquals("the second document waits for the reader to leave", listOf(1L), extracted)

            open.value = true
            delay(100)
        }

        assertTrue("the second changed document was never read", 2L in extracted)
    }
}
