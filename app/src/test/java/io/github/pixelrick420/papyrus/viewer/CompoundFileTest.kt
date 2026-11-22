package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class CompoundFileTest {

    private class Stalling(private val data: ByteArray, private val zeros: Int) : InputStream() {
        private var at = 0
        private var stalls = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (stalls++ < zeros) return 0
            if (at >= data.size) return -1
            b[off] = data[at++]
            return 1
        }
    }

    @Test
    fun `readFully tolerates zero reads that make progress`() {
        val dest = ByteArray(4)

        readFully(Stalling(byteArrayOf(1, 2, 3, 4), zeros = 3), dest, 0, dest.size)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), dest)
    }

    @Test
    fun `readFully refuses a stream that never advances`() {
        assertThrows(IOException::class.java) { readFully(Stalling(ByteArray(4), zeros = Int.MAX_VALUE), ByteArray(4), 0, 4) }
    }

    @Test
    fun `probeLength refuses a stream that never advances`() {
        assertThrows(IOException::class.java) { probeLength { Stalling(ByteArray(0), zeros = Int.MAX_VALUE) } }
    }

    @Test
    fun `probeLength counts a stream that stalls briefly`() {
        assertEquals(3L, probeLength { Stalling(byteArrayOf(1, 2, 3), zeros = MAX_STALLED_READS - 1) })
    }

    @Test
    fun `a 4096 byte sector compound file reads its streams`() {
        val payload = "4096-sector payload".toByteArray()
        val bytes = DocFixtures.compound(mapOf("Payload" to payload), sectorSize = 4096)

        val cfb = CompoundFile(DocFixtures.streamFactory(bytes))

        assertEquals(payload.size.toLong(), cfb.streamSize("Payload"))
        assertArrayEquals(payload, cfb.read("Payload", 0, payload.size))
    }

    private class CountingStreams(private val bytes: ByteArray) {
        var opens = 0
            private set

        val factory: () -> InputStream = {
            opens++
            ByteArrayInputStream(bytes)
        }
    }

    @Test
    fun `a bare name is root level and nested leaves are told apart by their storage`() {
        val root = "root payload".toByteArray()
        val left = "left payload".toByteArray()
        val right = "right payload".toByteArray()
        val bytes = DocFixtures.compound(
            mapOf("Payload" to root, "First\\Payload" to left, "Second/Payload" to right),
        )

        val cfb = CompoundFile(DocFixtures.streamFactory(bytes))

        assertArrayEquals(root, cfb.read("Payload", 0, root.size))
        assertArrayEquals(left, cfb.read("First\\Payload", 0, left.size))
        assertArrayEquals(left, cfb.read("First/Payload", 0, left.size))
        assertArrayEquals(right, cfb.read("Second/Payload", 0, right.size))
        assertFalse(cfb.hasStream("First"))
        assertNull(cfb.streamSize("Missing\\Payload"))
        assertThrows(IOException::class.java) { cfb.read("First", 0, 1) }
    }

    @Test
    fun `a known length skips the probing open`() {
        val bytes = DocFixtures.compound(mapOf("Payload" to "known length".toByteArray()))
        val probed = CountingStreams(bytes)
        val known = CountingStreams(bytes)

        CompoundFile(probed.factory)
        CompoundFile(known.factory, bytes.size.toLong())

        assertEquals(probed.opens, known.opens + 1)
    }

    @Test
    fun `one reader serves several forward reads from one open stream`() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = DocFixtures.compound(mapOf("Payload" to payload))
        val counting = CountingStreams(bytes)
        val cfb = CompoundFile(counting.factory, bytes.size.toLong())
        val opened = counting.opens

        cfb.openReader("Payload").use { reader ->
            assertArrayEquals(payload.copyOfRange(0, 100), reader.read(0, 100))
            assertArrayEquals(payload.copyOfRange(100, 200), reader.read(100, 100))
            assertArrayEquals(ByteArray(0), reader.read(200, 0))
        }

        // The mini-stream payload loads its mini FAT table through one extra open.
        assertEquals(opened + 2, counting.opens)
        assertThrows(IOException::class.java) { cfb.openReader("Payload").read(0, payload.size + 1) }
    }
}
