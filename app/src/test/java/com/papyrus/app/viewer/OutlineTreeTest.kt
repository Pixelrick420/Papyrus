package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTreeTest {

    private fun e(depth: Int, title: String = "t") = OutlineEntry(0, depth, title)

    /** 0 A / 1 A.1 / 2 A.1.1 / 3 A.2 / 4 B / 5 B.1 / 6 C */
    private val tree = listOf(e(0), e(1), e(2), e(1), e(0), e(1), e(0))

    @Test
    fun `an entry has children when the next one is deeper`() {
        assertTrue(hasChildren(tree, 0))
        assertTrue(hasChildren(tree, 1))
        assertFalse(hasChildren(tree, 2))
        assertFalse(hasChildren(tree, 3))
        assertTrue(hasChildren(tree, 4))
        assertFalse(hasChildren(tree, 6)) // the last entry has no next one
        assertFalse(hasChildren(tree, 99))
        assertFalse(hasChildren(tree, -1))
    }

    @Test
    fun `nothing folded shows everything`() {
        assertEquals((0..6).toList(), visibleOutline(tree, emptySet()))
    }

    @Test
    fun `folding an entry hides everything under it and nothing after`() {
        assertEquals(listOf(0, 4, 5, 6), visibleOutline(tree, setOf(0)))
        assertEquals(listOf(0, 1, 3, 4, 5, 6), visibleOutline(tree, setOf(1)))
        assertEquals(listOf(0, 1, 2, 3, 4, 6), visibleOutline(tree, setOf(4)))
    }

    @Test
    fun `a fold inside a folded entry changes nothing`() {
        assertEquals(listOf(0, 4, 5, 6), visibleOutline(tree, setOf(0, 1)))
    }

    @Test
    fun `folding something that is not a parent is ignored`() {
        assertEquals((0..6).toList(), visibleOutline(tree, setOf(2, 3, 6, 40)))
    }

    @Test
    fun `an empty outline shows nothing`() {
        assertEquals(emptyList<Int>(), visibleOutline(emptyList(), setOf(0)))
    }

    @Test
    fun `a short outline starts open and a long one starts folded to its top level`() {
        assertEquals(emptySet<Int>(), initialFolds(tree))
        val long = (0 until 40).flatMap { listOf(e(0), e(1), e(1)) } // 120 entries, every third a parent
        val folds = initialFolds(long)
        assertEquals(40, folds.size)
        assertEquals(40, visibleOutline(long, folds).size)
    }

    @Test
    fun `titles are cleaned to one bounded line`() {
        assertEquals("A B C", cleanOutlineTitle(" A\n B\t\tC "))
        assertEquals("", cleanOutlineTitle(" \n\t "))
        assertEquals(200, cleanOutlineTitle("y".repeat(900)).length)
    }
}
