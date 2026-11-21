package io.github.pixelrick420.papyrus.testutil

import java.io.InputStream

/** Wraps a stream factory and records how often it opens and how many bytes callers read. */
class CountingStreamFactory(private val openStream: () -> InputStream) {

    var opens: Int = 0
        private set

    var bytesRead: Long = 0L
        private set

    val factory: () -> InputStream = {
        opens++
        CountingInputStream(openStream())
    }

    fun reset() {
        opens = 0
        bytesRead = 0L
    }

    private inner class CountingInputStream(private val source: InputStream) : InputStream() {
        override fun read(): Int {
            val byte = source.read()
            if (byte >= 0) bytesRead++
            return byte
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(bytes, offset, length)
            if (count > 0) bytesRead += count
            return count
        }

        override fun skip(n: Long): Long {
            val count = source.skip(n)
            if (count > 0) bytesRead += count
            return count
        }

        override fun close() = source.close()
    }
}
