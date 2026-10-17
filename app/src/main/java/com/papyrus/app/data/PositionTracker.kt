package com.papyrus.app.data

/**
 * The save policy in one framework-free class, so its ordering bugs are unit-testable: the gate stays
 * shut until a restore is applied, the first position seen is a baseline, and saves are debounced.
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

    /** Most recent position seen while the gate was open: what a reload or rotation restores. */
    var latest: PositionSample? = null
        private set

    /**
     * Starts a load: the gate opens at once unless [restorePending], then only on [restoreApplied].
     */
    fun beginLoad(restorePending: Boolean) {
        open = !restorePending
        baseline = null
        moved = false
    }

    /** Records the position the viewer actually applied, opening the save gate. */
    fun restoreApplied(applied: PositionSample) {
        open = true
        baseline = applied
        latest = applied
        moved = false
    }

    /** The viewport now shows [sample]; called often, so keep it cheap. */
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
