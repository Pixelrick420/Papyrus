package com.papyrus.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills the content index in the background: extracts the text of every document that has none yet,
 * writes it to [SearchDao], and does it again whenever the library changes.
 *
 * The database is the queue. [SearchDao.pending] is the only source of truth about what is left to
 * do, so nothing is lost if the process dies mid-pass, a document is removed while its extraction
 * runs, or two passes overlap: the next pass simply re-reads the queue and skips what is already
 * indexed. That is also why [start] can be called without knowing what happened before — the first
 * pass after an upgrade is what picks up an index written by an older [INDEX_VERSION].
 *
 * Extraction is slow enough to be measured in minutes on a large library, so it runs one document at
 * a time, in small batches, on the caller's scope: no worker threads of its own, no wake locks, and
 * a normal process death just stops it. A document that fails is written as [TextIndexState.FAILED]
 * and never retried, which is what keeps one corrupt file from blocking the queue forever; bumping
 * [INDEX_VERSION] is the deliberate way to re-index everything.
 */
class SearchIndexer(
    private val dao: SearchDao,
    /**
     * Reads one document's searchable text: [DocumentTextExtractor.extract] in production. A
     * function rather than the extractor itself so the queue's behaviour can be tested without a
     * `Context` and without a file, neither of which this class knows anything about.
     */
    private val extract: suspend (DocumentEntity) -> ExtractedText,
    private val library: Flow<*>,
) {
    private val started = AtomicBoolean(false)

    /**
     * Starts indexing on [scope] and keeps doing it as [library] changes. Safe to call more than
     * once; only the first call has an effect. Call it off the main thread — the first thing it
     * touches is the database.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            indexPending()
            // Conflated on purpose: the signal only says "look at the library again", and a pass
            // that is already running re-reads the queue, so a burst of imports costs one extra
            // pass instead of one per document. The delay lets an import finish registering its
            // row before an extraction is started for it.
            library.conflate().collect {
                delay(IMPORT_SETTLE_MILLIS)
                indexPending()
            }
        }
    }

    /**
     * One pass: index every pending document, oldest first, until the queue is empty. Suspends
     * between documents, so a pass can be cancelled or take as long as the library needs.
     */
    suspend fun indexPending() {
        while (true) {
            val batch = dao.pending(INDEX_VERSION, BATCH_SIZE)
            if (batch.isEmpty()) return
            for (document in batch) {
                currentCoroutineContext().ensureActive()
                val extracted = extract(document)
                // Checked after the extraction, not before: reading a document can take seconds and
                // it may have been removed from the library in the meantime.
                if (dao.existingId(document.id) == null) continue
                dao.replace(
                    DocumentTextEntity(
                        documentId = document.id,
                        text = extracted.text,
                        state = extracted.state,
                        charCount = extracted.text.length,
                        sourceSize = document.sizeBytes,
                        indexedAt = System.currentTimeMillis(),
                        indexVersion = INDEX_VERSION,
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * Bumped whenever the stored text of the same document would come out different — a new
         * extractor, a changed text shape — which re-indexes the whole library on the next start.
         * It is also the only retry path for a [TextIndexState.FAILED] row.
         */
        const val INDEX_VERSION = 1

        /** Documents per queue read: bounded work between two looks at the library. */
        private const val BATCH_SIZE = 16

        /** How long an import may keep settling before its text is extracted. */
        private const val IMPORT_SETTLE_MILLIS = 1_000L
    }
}
