package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfEncryptionVerdictsTest {

    private val key = PdfEncryptionVerdictKey("content://doc/1", 1_024L, 500L)

    @Test
    fun anUnchangedSourceIsScannedOnce() {
        var scans = 0
        val verdicts = PdfEncryptionVerdicts { scans++; PdfEncryption.NOT_ENCRYPTED }

        repeat(3) { assertEquals(PdfEncryption.NOT_ENCRYPTED, verdicts.of(key)) }

        assertEquals(1, scans)
    }

    @Test
    fun aChangedSizeOrModifiedTimeIsScannedAgain() {
        var scans = 0
        val verdicts = PdfEncryptionVerdicts { scans++; PdfEncryption.NOT_ENCRYPTED }

        verdicts.of(key)
        verdicts.of(key)
        verdicts.of(key.copy(sizeBytes = 2_048L))
        verdicts.of(key.copy(modified = 501L))

        assertEquals(3, scans)
    }

    @Test
    fun aPasswordFailureIsNotScannedAgain() {
        var scans = 0
        val verdicts = PdfEncryptionVerdicts { scans++; PdfEncryption.USER_PASSWORD }

        repeat(3) { assertEquals(PdfEncryption.USER_PASSWORD, verdicts.of(key)) }

        assertEquals(1, scans)
    }

    @Test
    fun theOldestEntriesAreDroppedWhenTheCacheIsFull() {
        var scans = 0
        val verdicts = PdfEncryptionVerdicts(maxEntries = 2) { scans++; PdfEncryption.NOT_ENCRYPTED }

        verdicts.of(key)
        verdicts.of(key.copy(uri = "content://doc/2"))
        verdicts.of(key.copy(uri = "content://doc/3"))
        verdicts.of(key)

        assertEquals(4, scans)
    }
}
