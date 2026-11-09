package com.papyrus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills the content index in the background: extracts the text of every document that has none yet or
 * whose file has changed, writes it to [SearchDao], and does it again whenever the library changes.
 *
 * The database is the queue. [SearchDao.pending] is the source of truth about what is left to do, so
 * nothing is lost if the process dies mid-pass, a document is removed while its extraction runs, or
 * two passes overlap: the next pass simply re-reads the queue and skips what is already indexed.
 * That is also why [start] can be called without knowing what happened before - the first pass after
 * an upgrade is what picks up an index written by an older [INDEX_VERSION].
 *
 * A document that is already indexed is read again only if it changed, and that is told without opening
 * it: a different size than the library records for it is in the queue itself, and a different modified
 * time than the one stored with its text is found by [indexChanged], once per process, from what the
 * provider reports.
 *
 * Extraction is slow enough to be measured in minutes on a large library, so it runs one document at
 * a time, in small batches, and only while [mayIndex] says so: closing the gate cancels the pass
 * between two documents and opening it starts the queue again. No worker threads of its own and no
 * wake locks; a normal process death just stops it. A document that fails is written as
 * [TextIndexState.FAILED] and never retried unless its file changes, which is what keeps one corrupt
 * file from blocking the queue forever; bumping [INDEX_VERSION] is the deliberate way to re-index
 * everything.
 *
 * Small documents are the exception: a file of [EAGER_MAX_BYTES] or less is read without waiting for
 * the gate or the start delay, because its text is usually wanted right away. The one thing they do
 * wait for is [enabled]: with the setting off nothing is read, small files included, and turning it
 * back on reads them again without waiting for the gate. After the first one, the eager pass runs
 * again only for a small file that joins the library or changes size, see [eagerArrivals].
 */
class SearchIndexer(
    private val dao: SearchDao,
    /**
     * Reads one document's searchable text: [DocumentTextExtractor.extract] in production. A
     * function rather than the extractor itself so the queue's behaviour can be tested without a
     * `Context` and without a file, neither of which this class knows anything about.
     */
    private val extract: suspend (DocumentEntity) -> ExtractedText,
    /**
     * Every document in the library as `(id, size in bytes)`, emitted again whenever that list
     * changes. What counts as a change is the caller's to decide: a star or an open is not one. Each
     * emission runs the gated pass again; the eager pass looks only at the small files in it.
     */
    private val library: Flow<List<Pair<Long, Long>>>,
    /**
     * When the provider says the file behind a document was last modified, in milliseconds, or null
     * when it does not say. A function for the same reason as [extract]: the provider query needs a
     * `Context` this class does not have.
     */
    private val lastModified: suspend (DocumentEntity) -> Long? = { null },
    /**
     * Whether the person wants documents read at all: the setting, on its own. Unlike [mayIndex] it
     * also stops the eager pass. Needs a current value, as a state flow has; on by default.
     */
    private val enabled: Flow<Boolean> = flowOf(true),
    /** Indexing runs while this is true; see [IndexingGate]. Always on by default. */
    private val mayIndex: Flow<Boolean> = flowOf(true),
    /** Waited before the first pass, so indexing does not compete with the app starting up. */
    private val startDelayMillis: Long = 0,
    /** Files at most this large ignore [mayIndex], but not [enabled]; zero turns the eager pass off. */
    private val eagerMaxBytes: Long = EAGER_MAX_BYTES,
) {
    private val started = AtomicBoolean(false)

    /** One pass at a time, so the eager and gated passes do not extract the same document twice. */
    private val passLock = Mutex()

    /**
     * Set once [indexChanged] has gone through the whole library: a pass paused part way resumes the
     * check, a finished one does not repeat it.
     */
    private val changesChecked = AtomicBoolean(false)

    /**
     * Starts indexing: small files immediately, everything else as [mayIndex] allows. Call off the
     * main thread.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            indexEager()
            // Outside the gated collector, so closing the gate does not cancel the eager pass.
            launch {
                // The setting coming back on is a reason to look again too: a closed gate would
                // otherwise leave small files unread until the next library change.
                val settingOn = enabled.distinctUntilChanged().drop(1).filter { it }
                merge(library.eagerArrivals(eagerMaxBytes), settingOn).conflate().collect {
                    delay(IMPORT_SETTLE_MILLIS)
                    indexEager()
                }
            }
            if (startDelayMillis > 0) delay(startDelayMillis)
            // collectLatest: closing the gate cancels the pass in flight; opening it starts again.
            mayIndex.distinctUntilChanged().collectLatest { allowed ->
                if (allowed) indexWhileOpen()
            }
        }
    }

    private suspend fun indexWhileOpen() {
        indexPending()
        if (!changesChecked.get()) {
            indexChanged()
            changesChecked.set(true)
        }
        // Conflated with a settle delay so bursts of file imports coalesce into a single indexing pass.
        library.conflate().collect {
            delay(IMPORT_SETTLE_MILLIS)
            indexPending()
        }
    }

    /**
     * One pass over the pending documents at most [eagerMaxBytes] large; ignores the gate but not
     * [enabled], which is asked again before each document so switching it off stops the pass.
     */
    suspend fun indexEager() = passLock.withLock {
        drain(wanted = { enabled.first() }) { dao.pendingUpTo(INDEX_VERSION, eagerMaxBytes, BATCH_SIZE) }
    }

    /**
     * One pass: index every pending document, oldest first, until the queue is empty. Suspends
     * between documents, so a pass can be cancelled or take as long as the library needs.
     */
    suspend fun indexPending() = passLock.withLock {
        drain { dao.pending(INDEX_VERSION, BATCH_SIZE) }
    }

    /** Reads [batch] until it comes back empty, or until [wanted] says no, which ends the pass. */
    private suspend fun drain(
        wanted: suspend () -> Boolean = { true },
        batch: suspend () -> List<DocumentEntity>,
    ) {
        while (wanted()) {
            val documents = batch()
            if (documents.isEmpty()) return
            for (document in documents) {
                if (!wanted()) return
                indexOne(document, lastModified(document))
            }
        }
    }

    /**
     * Reads again every indexed document whose provider now reports another modified time than the one
     * its text was read at. Asks the provider only for that time, never for the file, so an untouched
     * library costs one small query per document; a document with no recorded time, or one the provider
     * has nothing to say about now, is left alone.
     */
    suspend fun indexChanged() = passLock.withLock {
        var after = 0L
        while (true) {
            val batch = dao.indexedWithModified(INDEX_VERSION, after, BATCH_SIZE)
            if (batch.isEmpty()) return@withLock
            for (row in batch) {
                yield()
                after = row.document.id
                val now = lastModified(row.document)?.takeIf { it > 0 } ?: continue
                if (now != row.sourceModified) indexOne(row.document, now)
            }
        }
    }

    /**
     * Extracts [document] and replaces its row. [modified] is what the provider said before the file
     * was opened, so an edit made while it was being read leaves the stored time behind the file's and
     * is found on the next check instead of being recorded as read.
     */
    private suspend fun indexOne(document: DocumentEntity, modified: Long?) {
        // Also the cancellation point: a gate that closed since the last document stops the pass here.
        yield()
        val extracted = extract(document)
        // Checked after the extraction, not before: reading a document can take seconds and
        // it may have been removed from the library in the meantime.
        if (dao.existingId(document.id) == null) return
        dao.replace(
            DocumentTextEntity(
                documentId = document.id,
                text = extracted.text,
                state = extracted.state,
                charCount = extracted.text.length,
                sourceSize = document.sizeBytes,
                indexedAt = System.currentTimeMillis(),
                indexVersion = INDEX_VERSION,
                sourceModified = modified?.takeIf { it > 0 } ?: 0,
            ),
        )
    }

    companion object {
        /**
         * Bumped whenever the stored text of the same document would come out different - a new
         * extractor, a changed text shape - which re-indexes the whole library on the next start.
         * It is also the only retry path for a [TextIndexState.FAILED] row.
         */
        const val INDEX_VERSION = 1

        /** Documents per queue read: bounded work between two looks at the library. */
        private const val BATCH_SIZE = 16

        /**
         * Largest file read without waiting for the gate. 512 KiB is the cut-off other background
         * indexers settle on; a file this size is quick to read and cheap to index.
         */
        const val EAGER_MAX_BYTES = 512L * 1024

        /** How long an import may keep settling before its text is extracted. */
        private const val IMPORT_SETTLE_MILLIS = 1_000L
    }
}

/**
 * The library changes the eager pass can do something about: a document of at most [maxBytes] that
 * was not in the list before at that size, because it just joined or its size just changed. Adding
 * a larger file, removing a document or any list that holds nothing new leaves nothing for the eager
 * pass to read, so none of them is announced. The first list counts as all new, which costs one
 * empty read when nothing is pending.
 */
internal fun Flow<List<Pair<Long, Long>>>.eagerArrivals(maxBytes: Long): Flow<Unit> = flow {
    var before = emptySet<Pair<Long, Long>>()
    this@eagerArrivals.collect { library ->
        val small = library.filterTo(HashSet()) { (_, size) -> size <= maxBytes }
        if (small.any { it !in before }) emit(Unit)
        before = small
    }
}
