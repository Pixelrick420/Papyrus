package io.github.pixelrick420.papyrus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRepositoryTest {

    @Test
    fun pptxIsEncryptable() {
        assertTrue(DocumentRepository.ENCRYPTABLE.contains(DocumentFormat.PPTX))
    }

    @Test
    fun releaseFailureKeepsCopyMarkedIndexed() {
        val order = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            completeIndexedRepoint(
                markIndexed = { order += "indexed" },
                release = {
                    order += "release"
                    throw IllegalStateException("grant lost")
                },
            )
        }

        assertEquals(listOf("indexed", "release"), order)
    }
}
