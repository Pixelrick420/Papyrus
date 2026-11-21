package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
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
}
