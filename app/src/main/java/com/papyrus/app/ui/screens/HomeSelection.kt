package com.papyrus.app.ui.screens

/** Which Home rows are picked; data so transitions are unit-tested and selection survives rotation. */
data class SelectionState(
    val active: Boolean = false,
    val ids: Set<Long> = emptySet(),
) {
    /** A long-press enters selection with exactly that row picked. */
    fun start(id: Long) = SelectionState(active = true, ids = setOf(id))

    /** Toggles [id]; while inactive this behaves as [start]. */
    fun toggle(id: Long): SelectionState {
        if (!active) return start(id)
        return copy(ids = if (id in ids) ids - id else ids + id)
    }

    /** Selects every visible row, or clears them when all are already picked. An empty view changes nothing. */
    fun toggleAll(visible: Set<Long>): SelectionState {
        val remove = visible.isNotEmpty() && ids.containsAll(visible)
        return copy(ids = if (remove) ids - visible else ids + visible)
    }

    fun clear() = SelectionState()
}
