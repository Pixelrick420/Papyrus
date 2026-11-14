package io.github.pixelrick420.papyrus.data

// The save gate stays shut until a restore is applied; the first position seen is a baseline; saves are debounced.
class PositionTracker(
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val now: () -> Long,
) {
    private var open = false
    private var baseline: PositionSample? = null
    private var moved = false
    private var dirty = false
    private var changedAt = 0L

    var latest: PositionSample? = null
        private set

    fun beginLoad(restorePending: Boolean) {
        open = !restorePending
        baseline = null
        moved = false
    }

    fun restoreApplied(applied: PositionSample) {
        open = true
        baseline = applied
        latest = applied
        moved = false
    }

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

    fun nextDelay(): Long? =
        if (!open || !dirty) null else (idleMillis - (now() - changedAt)).coerceAtLeast(0L)

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
