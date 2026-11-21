package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CacheSweeperTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `entries older than the cutoff are removed`() {
        val dir = temp.newFolder("cache")
        val old = File(dir, "old.pdf").also { it.writeText("old") }
        assertTrue(old.setLastModified(CUTOFF - 1))

        CacheSweeper.sweepOlderThan(dir, CUTOFF)

        assertFalse(old.exists())
    }

    @Test
    fun `entries from this process are kept`() {
        val dir = temp.newFolder("cache")
        val fresh = File(dir, "fresh.pdf").also { it.writeText("fresh") }
        assertTrue(fresh.setLastModified(CUTOFF))

        CacheSweeper.sweepOlderThan(dir, CUTOFF)

        assertTrue(fresh.exists())
    }

    @Test
    fun `a missing directory is a no-op`() {
        CacheSweeper.sweepOlderThan(File(temp.root, "never-created"), CUTOFF)
    }

    @Test
    fun `a prefix leaves other entries alone`() {
        val dir = temp.newFolder("cache")
        val match = File(dir, "pptx-old").also { it.mkdirs() }
        val other = File(dir, "office-old").also { it.mkdirs() }
        assertTrue(match.setLastModified(CUTOFF - 1))
        assertTrue(other.setLastModified(CUTOFF - 1))

        CacheSweeper.sweepOlderThan(dir, CUTOFF, "pptx-")

        assertFalse(match.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `symlinks are not followed`() {
        val dir = temp.newFolder("cache")
        val target = temp.newFile("outside.pdf").also { it.writeText("private") }
        assertTrue(target.setLastModified(CUTOFF - 1))
        val link = File(dir, "linked.pdf")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        CacheSweeper.sweepOlderThan(dir, CUTOFF)

        assertTrue(link.exists())
        assertEquals("private", target.readText())
    }

    private companion object {
        const val CUTOFF = 1_000_000L
    }
}
