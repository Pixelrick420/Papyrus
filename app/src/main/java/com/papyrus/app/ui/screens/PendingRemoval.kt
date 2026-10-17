package com.papyrus.app.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hides [T] until its undo window ends, so Undo has nothing to put back; [commit] runs exactly once
 * per [begin]. [commit] runs under this lock, so it must only start the removal, never call back in.
 */
internal class PendingRemoval<T : Any>(
    private val timer: Timer,
    private val windowMillis: Long,
    private val commit: (T) -> Unit,
) {
    fun interface Timer {
        fun schedule(delayMillis: Long, action: () -> Unit): Cancellation
    }

    fun interface Cancellation {
        fun cancel()
    }

    /** Unique per [begin], so an Undo or timeout aimed at a replaced entry sees it is stale. */
    class Entry<T>(val item: T, val token: Long)

    private val lock = Any()
    private var nextToken = 1L
    private var timeout: Cancellation? = null
    private val state = MutableStateFlow<Entry<T>?>(null)

    val pending: StateFlow<Entry<T>?> = state.asStateFlow()

    /** Hides [item] for the window; anything already pending is committed at once. */
    fun begin(item: T) = synchronized(lock) {
        finishLocked()
        val entry = Entry(item, nextToken++)
        state.value = entry
        timeout = timer.schedule(windowMillis) { expire(entry.token) }
    }

    /** Brings back the entry [token] names; false once it is no longer pending. */
    fun undo(token: Long): Boolean = synchronized(lock) {
        if (state.value?.token != token) return false
        timeout?.cancel()
        timeout = null
        state.value = null
        true
    }

    /** Commits whatever is pending now, for an owner about to go away; a no-op when nothing is. */
    fun flush() = synchronized(lock) { finishLocked() }

    private fun expire(token: Long) = synchronized(lock) {
        if (state.value?.token == token) finishLocked()
    }

    /**
     * Commits the pending entry before clearing it, so no observer sees "nothing pending" too early.
     * Cleared even if [commit] throws, so a failure cannot lead to a second commit.
     */
    private fun finishLocked() {
        val entry = state.value ?: return
        timeout?.cancel()
        timeout = null
        try {
            commit(entry.item)
        } finally {
            state.value = null
        }
    }
}
