package com.papyrus.app.ui.screens

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The multi-file actions on Home that can take long enough to show how far they have got. */
enum class BulkAction { REMOVE, SHARE }

/**
 * How far a multi-file [action] has got, once it has run long enough to be worth showing. Null in
 * [HomeUiState.bulkProgress] means nothing to show: no bulk action is running, or it has not yet
 * lasted [BULK_PROGRESS_DELAY_MILLIS].
 */
data class BulkProgress(val action: BulkAction, val done: Int, val total: Int) {
    /** 0 to 1, for a determinate bar. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * How long a bulk action runs before its progress appears. A remove of a handful of files, or a
 * share whose files all answer at once, finishes inside this and never flashes a bar; one that does
 * not is slow enough that the person is wondering whether anything happened.
 */
internal const val BULK_PROGRESS_DELAY_MILLIS = 400L

/**
 * Counts the steps of one bulk action and publishes them, but only after [reveal]: before that
 * [advance] counts silently. Safe to advance from several coroutines, since a share probes its files
 * concurrently; counting and publishing happen under one lock so a slower thread can never publish a
 * lower count after a higher one.
 */
internal class BulkProgressTracker(
    private val action: BulkAction,
    private val total: Int,
    private val publish: (BulkProgress?) -> Unit,
) {
    private var done = 0
    private var shown = false

    /** One more step finished. */
    fun advance() = synchronized(this) {
        done++
        if (shown) publish(BulkProgress(action, done, total))
    }

    /** From now on every step is published, starting with the count so far. */
    fun reveal() = synchronized(this) {
        shown = true
        publish(BulkProgress(action, done, total))
    }

    /** Takes the progress away again, whether the action finished, failed or was cancelled. */
    fun finish() = synchronized(this) {
        shown = false
        publish(null)
    }
}

/**
 * Runs [block] and shows its progress through [publish] only if it lasts longer than [showAfterMillis].
 * [block] calls [BulkProgressTracker.advance] once per file. The progress is always taken away again
 * when [block] ends, however it ends.
 */
internal suspend fun <R> trackBulk(
    action: BulkAction,
    total: Int,
    publish: (BulkProgress?) -> Unit,
    showAfterMillis: Long = BULK_PROGRESS_DELAY_MILLIS,
    block: suspend (BulkProgressTracker) -> R,
): R = coroutineScope {
    val tracker = BulkProgressTracker(action, total, publish)
    val reveal = launch {
        delay(showAfterMillis)
        tracker.reveal()
    }
    try {
        block(tracker)
    } finally {
        reveal.cancel()
        tracker.finish()
    }
}
