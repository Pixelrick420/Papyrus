package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.CancellationException
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
    private val onDocumentFailure: (documentId: Long, failureClass: String) -> Unit = { _, _ -> },
) {
    private val started = AtomicBoolean(false)

    /** One pass at a time, so the eager and gated passes do not extract the same document twice. */
    private val passLock = Mutex()

    // Set once [indexChanged] has scanned the library; an interrupted pass restarts from the first row.
    private val changesChecked = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val job = scope.launch {
            if (startDelayMillis > 0) delay(startDelayMillis)
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
            indexEager()
            mayIndex.distinctUntilChanged().collectLatest { allowed ->
                if (allowed) indexWhileOpen()
            }
        }
        job.invokeOnCompletion { started.set(false) }
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
        drain(wanted = { enabled.first() }) {
            dao.pendingUpTo(INDEX_VERSION, eagerMaxBytes, System.currentTimeMillis(), BATCH_SIZE)
        }
    }

    suspend fun indexPending() = passLock.withLock {
        drain { dao.pending(INDEX_VERSION, System.currentTimeMillis(), BATCH_SIZE) }
    }

    private suspend fun drain(
        wanted: suspend () -> Boolean = { true },
        batch: suspend () -> List<DocumentEntity>,
    ) {
        // A document whose FAILED row could not be written stays in the DB queue; drop it for this pass only.
        val unwritable = HashSet<Long>()
        while (wanted()) {
            val documents = batch().filterNot { it.id in unwritable }
            if (documents.isEmpty()) return
            for (document in documents) {
                if (!wanted()) return
                if (!indexSafely(document, safeModified(document))) unwritable += document.id
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
                val now = safeModified(row.document)?.takeIf { it > 0 } ?: continue
                if (now != row.sourceModified) indexSafely(row.document, now)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun safeModified(document: DocumentEntity): Long? =
        try {
            lastModified(document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            onDocumentFailure(document.id, failure.javaClass.simpleName)
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun indexSafely(document: DocumentEntity, modified: Long?): Boolean = try {
        indexOne(document, modified)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onDocumentFailure(document.id, failure.javaClass.simpleName)
        val failures = dao.failureCount(document.id) + 1
        val row = failedRow(document, modified, failures, System.currentTimeMillis())
        try {
            dao.replaceIfExists(row)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    // [modified] is read before opening the file, so an edit made during reading is found on the next check.
    private suspend fun indexOne(document: DocumentEntity, modified: Long?) {
        // Also the cancellation point: a gate that closed since the last document stops the pass here.
        yield()
        val extracted = extract(document)
        val now = System.currentTimeMillis()
        val failed = extracted.state == TextIndexState.FAILED
        val row = if (failed) {
            failedRow(document, modified, dao.failureCount(document.id) + 1, now)
        } else {
            DocumentTextEntity(
                documentId = document.id,
                text = extracted.text,
                state = extracted.state,
                charCount = extracted.text.length,
                sourceSize = document.sizeBytes,
                indexedAt = now,
                indexVersion = INDEX_VERSION,
                sourceModified = modified?.takeIf { it > 0 } ?: 0,
            )
        }
        // The document may have been removed while its text was being read, so the write rechecks the parent.
        dao.replaceIfExists(row)
    }

    // A failed row waits for its deadline and then the next pass; after the last step it stays skipped.
    private fun failedRow(
        document: DocumentEntity,
        modified: Long?,
        failures: Int,
        now: Long,
    ): DocumentTextEntity {
        val terminal = failures >= MAX_FAILURES
        return DocumentTextEntity(
            documentId = document.id,
            text = "",
            state = if (terminal) TextIndexState.SKIPPED else TextIndexState.FAILED,
            charCount = 0,
            sourceSize = document.sizeBytes,
            indexedAt = if (terminal) now else now + RETRY_DELAYS[failures - 1],
            indexVersion = INDEX_VERSION,
            sourceModified = modified?.takeIf { it > 0 } ?: 0,
            failureCount = failures,
        )
    }

    companion object {
        // Bump whenever the stored text of a document would come out different; it re-indexes the library.
        const val INDEX_VERSION = 1

        const val FAILED_RETRY_MILLIS = 5 * 60 * 1_000L

        private val RETRY_DELAYS = longArrayOf(
            FAILED_RETRY_MILLIS,
            30 * 60 * 1_000L,
            2 * 60 * 60 * 1_000L,
            24 * 60 * 60 * 1_000L,
        )

        private const val MAX_FAILURES = 5

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
        // A provider that reports no size must not be mistaken for a tiny file.
        val small = library.filterTo(HashSet()) { (_, size) -> size in 1..maxBytes }
        if (small.any { it !in before }) emit(Unit)
        before = small
    }
}
