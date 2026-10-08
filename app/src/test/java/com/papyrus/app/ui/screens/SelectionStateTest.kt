package com.papyrus.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bulk actions act on [SelectionState.ids], so the transitions that build it are pinned here. */
class SelectionStateTest {

    @Test
    fun `start activates with exactly the pressed row`() {
        val state = SelectionState().start(7L)
        assertTrue(state.active)
        assertEquals(setOf(7L), state.ids)
    }

    @Test
    fun `toggle on an inactive state starts selection`() {
        val state = SelectionState().toggle(3L)
        assertTrue(state.active)
        assertEquals(setOf(3L), state.ids)
    }

    @Test
    fun `toggling the same id again removes it and stays active`() {
        val state = SelectionState().start(3L).toggle(3L)
        assertTrue(state.active)
        assertTrue(state.ids.isEmpty())
    }

    @Test
    fun `toggle all adds every visible id when not all are selected`() {
        val state = SelectionState().start(1L).toggleAll(setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), state.ids)
    }

    @Test
    fun `toggle all removes every visible id when all are already selected`() {
        val state = SelectionState(active = true, ids = setOf(1L, 2L, 3L)).toggleAll(setOf(1L, 2L, 3L))
        assertTrue(state.ids.isEmpty())
    }

    @Test
    fun `toggle all keeps an off-screen selection untouched`() {
        val state = SelectionState(active = true, ids = setOf(1L, 9L)).toggleAll(setOf(1L, 2L))
        assertEquals(setOf(1L, 2L, 9L), state.ids)
    }

    @Test
    fun `toggle all over nothing changes nothing`() {
        val state = SelectionState(active = true, ids = setOf(1L, 2L)).toggleAll(emptySet())
        assertEquals(setOf(1L, 2L), state.ids)
    }

    @Test
    fun `clear deactivates and empties`() {
        val state = SelectionState(active = true, ids = setOf(1L, 2L)).clear()
        assertFalse(state.active)
        assertTrue(state.ids.isEmpty())
    }
}
