package com.papyrus.app.ui.screens

data class SelectionState(
    val active: Boolean = false,
    val ids: Set<Long> = emptySet(),
) {
    fun start(id: Long) = SelectionState(active = true, ids = setOf(id))

    fun toggle(id: Long): SelectionState {
        if (!active) return start(id)
        return copy(ids = if (id in ids) ids - id else ids + id)
    }

    fun toggleAll(visible: Set<Long>): SelectionState {
        val remove = visible.isNotEmpty() && ids.containsAll(visible)
        return copy(ids = if (remove) ids - visible else ids + visible)
    }

    fun clear() = SelectionState()
}
