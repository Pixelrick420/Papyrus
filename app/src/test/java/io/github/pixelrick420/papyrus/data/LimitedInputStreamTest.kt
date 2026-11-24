package io.github.pixelrick420.papyrus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class LimitedInputStreamTest {

    @Test
    fun `a source at the limit is read fully`() {
        LimitedInputStream(ByteArrayInputStream("abc".toByteArray()), 3).use { stream ->
            assertEquals("abc", String(stream.readBytes()))
        }
    }

    @Test
    fun `a source larger than the limit is reported`() {
        assertThrows(SourceLimitExceeded::class.java) {
            LimitedInputStream(ByteArrayInputStream("abcdef".toByteArray()), 3).use { stream ->
                stream.readBytes()
            }
        }
    }

    @Test
    fun `skipping counts against the limit`() {
        assertThrows(SourceLimitExceeded::class.java) {
            LimitedInputStream(ByteArrayInputStream("abcdef".toByteArray()), 3).use { stream ->
                stream.skip(3)
                stream.read()
            }
        }
    }
}
