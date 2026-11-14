package io.github.pixelrick420.papyrus.data

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

// The database is the queue: [SearchDao.pending] is the source of truth, so a dying process, a removal mid-extraction or overlapping passes lose nothing.
class SearchIndexer(
    private val dao: SearchDao,
    // A function so the queue can be tested without a Context or a file.
    private val extract: suspend (DocumentEntity) -> ExtractedText,
    // What counts as a change is the caller's to decide: a star or an open is not one.
    private val library: Flow<List<Pair<Long, Long>>>,
    // A function for the same reason as [extract]: the provider query needs a Context.
    private val lastModified: suspend (DocumentEntity) -> Long? = { null },
    // Unlike [mayIndex], this also stops the eager pass.
    private val enabled: Flow<Boolean> = flowOf(true),
    private val mayIndex: Flow<Boolean> = flowOf(true),
    private val startDelayMillis: Long = 0,
    /** Files at most this large ignore [mayIndex], but not [enabled]; zero turns the eager pass off. */
    private val eagerMaxBytes: Long = EAGER_MAX_BYTES,
) {
    private val started = AtomicBoolean(false)

    /** One pass at a time, so the eager and gated passes do not extract the same document twice. */
    private val passLock = Mutex()

    // Set once [indexChanged] has gone through the whole library: a pass paused part way resumes the
    // check, a finished one does not repeat it.
    private val changesChecked = AtomicBoolean(false)

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

    // Ignores the gate but not [enabled], which is re-asked before each document.
    suspend fun indexEager() = passLock.withLock {
        drain(wanted = { enabled.first() }) { dao.pendingUpTo(INDEX_VERSION, eagerMaxBytes, BATCH_SIZE) }
    }

    suspend fun indexPending() = passLock.withLock {
        drain { dao.pending(INDEX_VERSION, BATCH_SIZE) }
    }

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

    // Asks the provider only for the modified time, never the file; a document with no recorded time is left alone.
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

    // [modified] is read before opening the file, so an edit made during reading is found on the next check.
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
        // Bump whenever the stored text of a document would come out different; it re-indexes the library and is the only retry path for [FAILED].
        const val INDEX_VERSION = 1

        private const val BATCH_SIZE = 16

        const val EAGER_MAX_BYTES = 512L * 1024

        private const val IMPORT_SETTLE_MILLIS = 1_000L
    }
}

// Announces only a document of at most [maxBytes] that is new at that size; larger files, removals and lists with nothing new leave nothing to read.
// The first list counts as all new.
internal fun Flow<List<Pair<Long, Long>>>.eagerArrivals(maxBytes: Long): Flow<Unit> = flow {
    var before = emptySet<Pair<Long, Long>>()
    this@eagerArrivals.collect { library ->
        val small = library.filterTo(HashSet()) { (_, size) -> size <= maxBytes }
        if (small.any { it !in before }) emit(Unit)
        before = small
    }
}
