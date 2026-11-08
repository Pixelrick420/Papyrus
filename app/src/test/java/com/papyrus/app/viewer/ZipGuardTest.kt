package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The one stream every Word, OpenDocument and EPUB reader walks a ZIP through. */
class ZipGuardTest {

    private class Refused(reason: String) : IOException(reason)

    private val small = ZipLimits(
        maxEntries = 3,
        maxEntryBytes = 1_000,
        maxTotalBytes = 2_500,
        maxRatio = 10,
        ratioFloorBytes = 100,
    )

    /** Bytes that do not compress, so only the size limits can be what a test reaches. */
    private fun noise(size: Int) = kotlin.random.Random(SEED).nextBytes(size)

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun walk(bytes: ByteArray, limits: ZipLimits, read: Boolean = true): List<String> {
        val names = mutableListOf<String>()
        BoundedZipInputStream(ByteArrayInputStream(bytes), limits).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                if (read) zip.readBytes()
            }
        }
        return names
    }

    private fun refusal(bytes: ByteArray, limits: ZipLimits, read: Boolean = true): String? =
        runCatching { walk(bytes, limits, read) }.exceptionOrNull()?.message

    @Test
    fun `an ordinary archive is read whole, entry by entry`() {
        val bytes = zip("a.txt" to noise(300), "b.txt" to noise(400))
        val content = mutableMapOf<String, ByteArray>()

        BoundedZipInputStream(ByteArrayInputStream(bytes), small).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                content[entry.name] = zip.readBytes()
            }
        }

        assertEquals(setOf("a.txt", "b.txt"), content.keys)
        assertEquals(300, content.getValue("a.txt").size)
        assertEquals(400, content.getValue("b.txt").size)
    }

    @Test
    fun `as many entries as the limit allows are read and one more is refused`() {
        val three = zip("1" to ByteArray(0), "2" to ByteArray(0), "3" to ByteArray(0))
        val four = zip("1" to ByteArray(0), "2" to ByteArray(0), "3" to ByteArray(0), "4" to ByteArray(0))

        assertEquals(listOf("1", "2", "3"), walk(three, small))
        assertEquals("too many entries", refusal(four, small))
    }

    @Test
    fun `an entry past its cap is refused`() {
        assertNull(refusal(zip("ok" to noise(1_000)), small))
        assertEquals("too large", refusal(zip("big" to noise(1_001)), small))
    }

    @Test
    fun `the total across entries is capped even when no entry is`() {
        val bytes = zip("1" to noise(900), "2" to noise(900), "3" to noise(900))

        assertEquals("too large", refusal(bytes, small))
    }

    @Test
    fun `an entry that compresses implausibly well is refused once past the floor`() {
        val loose = small.copy(maxEntryBytes = 1_000_000, maxTotalBytes = 1_000_000)

        assertEquals("implausible compression", refusal(zip("bomb" to ByteArray(50_000)), loose))
    }

    @Test
    fun `the ratio is not judged below the floor`() {
        // 90 zeros compress far better than 10:1, but a few bytes of header make that meaningless.
        assertNull(refusal(zip("tiny" to ByteArray(90)), small))
    }

    @Test
    fun `an entry the caller never reads is still counted when the next one is asked for`() {
        val loose = small.copy(maxEntryBytes = 1_000_000, maxTotalBytes = 1_000_000)
        val bytes = zip("bomb" to ByteArray(50_000), "after" to noise(10))

        assertEquals("implausible compression", refusal(bytes, loose, read = false))
    }

    @Test
    fun `the size and ratio of one entry do not count against the next`() {
        val loose = small.copy(maxEntryBytes = 600, maxTotalBytes = 1_000_000)
        val bytes = zip("1" to noise(500), "2" to noise(500), "3" to noise(500))

        assertEquals(listOf("1", "2", "3"), walk(bytes, loose))
    }

    @Test
    fun `a breach throws what the caller's factory makes from the reason`() {
        val bytes = zip("big" to noise(1_001))

        val thrown = runCatching {
            BoundedZipInputStream(ByteArrayInputStream(bytes), small) { reason -> Refused(reason) }.use { it.nextEntry; it.readBytes() }
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is Refused)
        assertEquals("too large", thrown?.message)
    }

    @Test
    fun `the epub limits are these limits, so the two readers cannot drift apart`() {
        assertEquals(ZipLimits(), EpubLimits().zipLimits)
        assertEquals(small, EpubLimits(maxEntries = 3, maxEntryBytes = 1_000, maxTotalBytes = 2_500, maxRatio = 10, ratioFloorBytes = 100).zipLimits)
    }

    private companion object {
        const val SEED = 20_261_109
    }
}
