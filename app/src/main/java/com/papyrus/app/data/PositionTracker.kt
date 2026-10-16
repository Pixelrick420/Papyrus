package com.papyrus.app.data

/**
 * Decides when a reading position may be saved. It is the whole save policy in one framework-free
 * class, so the ordering bugs it exists to prevent can be unit-tested without Compose or a database.
 *
 * **The classic bug.** Content loads, the list sits at the top, a save fires, and the stored position is
 * overwritten before the restore was applied. So the gate is shut until either the restore has been
 * applied ([restoreApplied]) or [beginLoad] was told there is nothing to restore. While it is shut,
 * [observe] is ignored entirely.
 *
 * **No movement, no write.** The first thing seen after the gate opens is the starting point
 * ("baseline"), not a save. Opening a document and leaving without scrolling therefore writes nothing,
 * and the previous position stays. Once the reader has moved, every later position is saved, even a
 * return to the starting point, because that is where they stopped.
 *
 * **Debounce.** A save is due only after [idleMillis] without further movement; [take] with `force`
 * hands over the pending position at once for the paths that cannot wait (the app going to the
 * background, the viewer closing).
 *
 * Not thread-safe: it is meant to be driven from the main thread, where the viewport is observed.
 * [now] is injected so tests control time.
 */
class PositionTracker(
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val now: () -> Long,
) {
    private var open = false
    private var baseline: PositionSample? = null
    private var moved = false
    private var dirty = false
    private var changedAt = 0L

    /** The most recent position seen while the gate was open: what to restore after a reload or rotation. */
    var latest: PositionSample? = null
        private set

    /**
     * A new load of the content started. With [restorePending] the gate stays shut until
     * [restoreApplied]; without it there is nothing to restore and the gate opens now. An unsaved
     * position from before the load is kept, and written once the gate is open again.
     */
    fun beginLoad(restorePending: Boolean) {
        open = !restorePending
        baseline = null
        moved = false
    }

    /** The viewer placed the document at [applied] (as read back from the viewport after scrolling). */
    fun restoreApplied(applied: PositionSample) {
        open = true
        baseline = applied
        latest = applied
        moved = false
    }

    /** The viewport now shows [sample]. Cheap: called as often as the position changes. */
    fun observe(sample: PositionSample) {
        if (!open) return
        val base = baseline
        if (base == null) {
            baseline = sample
            latest = sample
            return
        }
        if (sample == latest) return // an unchanged position must not restart the debounce
        latest = sample
        if (!moved && sample.isSameSpot(base)) return
        moved = true
        dirty = true
        changedAt = now()
    }

    /** Milliseconds until a save is due, 0 if overdue, `null` when there is nothing to save. */
    fun nextDelay(): Long? =
        if (!open || !dirty) null else (idleMillis - (now() - changedAt)).coerceAtLeast(0L)

    /**
     * The position to save, or `null`. Hands each position over once. Without [force] it waits for
     * [idleMillis] of stillness; with it, any unsaved position is returned immediately.
     */
    fun take(force: Boolean): PositionSample? {
        if (!open || !dirty) return null
        if (!force && now() - changedAt < idleMillis) return null
        dirty = false
        return latest
    }

    companion object {
        /** About a second and a half: long enough not to write while scrolling, short enough to lose little. */
        const val DEFAULT_IDLE_MILLIS = 1_500L
    }
}
