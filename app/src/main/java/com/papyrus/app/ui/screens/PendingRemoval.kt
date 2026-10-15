package com.papyrus.app.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A removal that waits out an undo window before it happens, for the ViewModel to own.
 *
 * The thing being removed ([T], one row or a batch) stays untouched while it is pending: the list
 * merely hides it, so Undo has nothing to put back and "exactly as it was", SAF grant and file
 * included, holds by construction. Only [commit] does the real work, and this class guarantees it runs
 * **exactly once per [begin]**, whichever way the window ends: the timer firing, a newer [begin]
 * replacing it (which commits the older one at once), or [flush] when the owner is going away. An
 * [undo] is the only way out that never commits. Each path takes the entry out under one lock before
 * acting, so two of them racing cannot both see it.
 *
 * Nothing is held across process death. A process killed mid-window leaves the row in the library,
 * which is the safe default: the person can remove it again.
 *
 * The timer is injected so a test can fire it by hand. [commit] is not suspending and runs under this
 * class's lock, so it must only *start* the real removal (and must not call back in here): the owner
 * decides where that runs, and it has to outlive a cleared ViewModel to honour [flush].
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

    /** [token] is unique per [begin], so an Undo or a timeout aimed at a replaced entry can tell it is stale. */
    class Entry<T>(val item: T, val token: Long)

    private val lock = Any()
    private var nextToken = 1L
    private var timeout: Cancellation? = null
    private val state = MutableStateFlow<Entry<T>?>(null)

    /** The entry currently hidden and awaiting its window, or null. */
    val pending: StateFlow<Entry<T>?> = state.asStateFlow()

    /** Hides [item] for the window. Anything already pending is committed immediately, so there is at most one. */
    fun begin(item: T) = synchronized(lock) {
        finishLocked()
        val entry = Entry(item, nextToken++)
        state.value = entry
        timeout = timer.schedule(windowMillis) { expire(entry.token) }
    }

    /** Brings back the entry [token] names; false when it is no longer pending (committed, replaced or already undone). */
    fun undo(token: Long): Boolean = synchronized(lock) {
        if (state.value?.token != token) return false
        timeout?.cancel()
        timeout = null
        state.value = null
        true
    }

    /** Commits whatever is pending now, for an owner about to go away. A no-op when nothing is. */
    fun flush() = synchronized(lock) { finishLocked() }

    private fun expire(token: Long) = synchronized(lock) {
        if (state.value?.token == token) finishLocked()
    }

    /**
     * Commits the pending entry, if any, and only then stops reporting it as pending, so an observer
     * never sees "nothing pending" before the commit has started. The entry is cleared even if
     * [commit] throws, so a failure cannot lead to a second commit.
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
