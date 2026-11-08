package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A decrypted copy is plaintext in cache, so a copy the viewer lets go of must not be left behind. */
class UnlockedCopyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val copy = UnlockedCopy()

    private fun plaintext(name: String): File = temp.newFile(name).also { it.writeText("secret") }

    @Test
    fun `nothing is held to begin with`() {
        assertFalse(copy.isHeld)
        assertNull(copy.uri)
    }

    @Test
    fun `a second unlock deletes the first copy and keeps the second`() {
        val first = plaintext("first.pdf")
        val second = plaintext("second.pdf")

        copy.keep(first, "file:first")
        copy.keep(second, "file:second")

        assertFalse("the first copy would be stranded in cache", first.exists())
        assertTrue(second.exists())
        assertEquals("file:second", copy.uri)
        assertTrue(copy.isHeld)
    }

    @Test
    fun `keeping the copy it already holds does not delete it`() {
        val only = plaintext("only.pdf")

        copy.keep(only, "file:only")
        copy.keep(only, "file:only")

        assertTrue(only.exists())
    }

    @Test
    fun `discarding deletes the copy and forgets it`() {
        val only = plaintext("only.pdf")
        copy.keep(only, "file:only")

        copy.discard()

        assertFalse(only.exists())
        assertFalse(copy.isHeld)
        assertNull(copy.uri)
    }

    @Test
    fun `discarding with nothing held is harmless`() {
        copy.discard()

        assertFalse(copy.isHeld)
    }

    @Test
    fun `a copy kept after a discard is held again`() {
        copy.keep(plaintext("one.pdf"), "file:one")
        copy.discard()
        val two = plaintext("two.pdf")

        copy.keep(two, "file:two")

        assertTrue(two.exists())
        assertEquals("file:two", copy.uri)
    }
}
