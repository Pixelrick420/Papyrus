package io.github.pixelrick420.papyrus.ui.screens

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BulkAction { REMOVE, SHARE }

data class BulkProgress(val action: BulkAction, val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

// A quick bulk action finishes inside this and never flashes a bar; a slower one makes the person wonder whether anything happened.
internal const val BULK_PROGRESS_DELAY_MILLIS = 400L

// Counting and publishing share one lock (a share probes its files concurrently), so a slower thread can never publish a lower count after a higher one.
internal class BulkProgressTracker(
    private val action: BulkAction,
    private val total: Int,
    private val publish: (BulkProgress?) -> Unit,
) {
    private var done = 0
    private var shown = false

    fun advance() = synchronized(this) {
        done++
        if (shown) publish(BulkProgress(action, done, total))
    }

    fun reveal() = synchronized(this) {
        shown = true
        publish(BulkProgress(action, done, total))
    }

    fun finish() = synchronized(this) {
        shown = false
        publish(null)
    }
}

// The progress is always taken away again when [block] ends, however it ends.
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
