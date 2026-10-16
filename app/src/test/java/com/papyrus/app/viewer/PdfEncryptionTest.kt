package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The failure modes matter most: a failed decrypt must leave no plaintext behind. */
class PdfEncryptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun onePage(): PDDocument = PDDocument().apply { addPage(PDPage()) }

    private fun plainPdf(): ByteArray = ByteArrayOutputStream().also { out ->
        onePage().use { it.save(out) }
    }.toByteArray()

    /** [ownerPassword] set with an empty [userPassword] is the permissions-only case. */
    private fun protectedPdf(ownerPassword: String, userPassword: String): ByteArray =
        ByteArrayOutputStream().also { out ->
            onePage().use { document ->
                val policy = StandardProtectionPolicy(ownerPassword, userPassword, AccessPermission())
                policy.encryptionKeyLength = 128
                document.protect(policy)
                document.save(out)
            }
        }.toByteArray()

    private fun factory(bytes: ByteArray): () -> ByteArrayInputStream = { ByteArrayInputStream(bytes) }

    @Test
    fun `a plain pdf is not encrypted`() {
        assertEquals(PdfEncryption.NOT_ENCRYPTED, PdfEncryption.status(factory(plainPdf())))
    }

    @Test
    fun `an owner-only pdf opens without a prompt`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "")
        assertEquals(PdfEncryption.OWNER_ONLY, PdfEncryption.status(factory(bytes)))
    }

    @Test
    fun `a user password is reported as needed`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "user-secret")
        assertEquals(PdfEncryption.USER_PASSWORD, PdfEncryption.status(factory(bytes)))
    }

    @Test
    fun `garbage is treated as not encrypted so the viewer reports it generically`() {
        assertEquals(PdfEncryption.NOT_ENCRYPTED, PdfEncryption.status(factory("not a pdf at all".toByteArray())))
    }

    @Test
    fun `the right password produces a readable, unencrypted copy`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "user-secret")
        val target = temp.newFile("unlocked.pdf")

        PdfEncryption.decrypt(factory(bytes), "user-secret", target)

        assertTrue(target.length() > 0)
        PDDocument.load(target).use { document ->
            assertFalse(document.isEncrypted)
            assertEquals(1, document.numberOfPages)
        }
    }

    @Test
    fun `the owner password also unlocks a user-password file`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "user-secret")
        val target = temp.newFile("unlocked-owner.pdf")

        PdfEncryption.decrypt(factory(bytes), "owner-secret", target)

        PDDocument.load(target).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun `a wrong password throws and leaves no plaintext behind`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "user-secret")
        val target = temp.newFile("wrong.pdf")
        target.delete()

        var threw = false
        try {
            PdfEncryption.decrypt(factory(bytes), "guess", target)
        } catch (e: InvalidPasswordException) {
            threw = true
        }

        assertTrue("a wrong password must surface as InvalidPasswordException", threw)
        assertFalse("a failed decrypt must not leave the target file on disk", target.exists())
    }

    @Test
    fun `an existing target is removed when decryption fails`() {
        val bytes = protectedPdf(ownerPassword = "owner-secret", userPassword = "user-secret")
        val target = temp.newFile("preexisting.pdf").apply { writeText("stale") }

        try {
            PdfEncryption.decrypt(factory(bytes), "guess", target)
        } catch (ignored: InvalidPasswordException) {
        }

        assertFalse(target.exists())
    }

    @Test
    fun `a plain pdf decrypts with an empty password into a readable copy`() {
        val target: File = temp.newFile("plain-copy.pdf")

        PdfEncryption.decrypt(factory(plainPdf()), "", target)

        PDDocument.load(target).use { assertFalse(it.isEncrypted) }
    }

    // ---- the byte screen that spares an unencrypted PDF a whole-file PdfBox load ----

    /** A source that counts how often it is opened: the plain-PDF path must open it once, to scan, and no more. */
    private class CountingSource(private val bytes: ByteArray) : () -> InputStream {
        var opens = 0
            private set

        override fun invoke(): InputStream {
            opens++
            return ByteArrayInputStream(bytes)
        }
    }

    private fun containsAscii(bytes: ByteArray, text: String): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains(text)

    private fun indexOfAscii(bytes: ByteArray, text: String): Int =
        String(bytes, Charsets.ISO_8859_1).indexOf(text)

    @Test
    fun `the fixtures really are linearized and xref-stream files`() {
        // Guards the fixtures themselves: a regenerated file that lost its layout would make the tests below vacuous.
        assertTrue(containsAscii(PdfEncryptionFixtures.linearizedPlain, "/Linearized"))
        assertTrue(containsAscii(PdfEncryptionFixtures.linearizedUserPassword, "/Linearized"))
        assertTrue(containsAscii(PdfEncryptionFixtures.linearizedOwnerOnly, "/Linearized"))
        for (xref in listOf(
            PdfEncryptionFixtures.xrefStreamPlain,
            PdfEncryptionFixtures.xrefStreamUserPassword,
            PdfEncryptionFixtures.xrefStreamOwnerOnly,
        )) {
            assertTrue(Regex("/Type\\s*/XRef").containsMatchIn(String(xref, Charsets.ISO_8859_1)))
            assertFalse("no classic xref table", containsAscii(xref, "\nxref\n"))
        }
    }

    @Test
    fun `a linearized pdf keeps its Encrypt key near the start`() {
        // The hazard a tail-only look would miss, now measured on a real file rather than asserted.
        val at = indexOfAscii(PdfEncryptionFixtures.linearizedUserPassword, "/Encrypt")
        assertTrue("expected /Encrypt in the first kilobyte, was at $at", at in 0..1023)
    }

    @Test
    fun `plain linearized and xref-stream pdfs are not encrypted and are never loaded by PdfBox`() {
        for (bytes in listOf(
            plainPdf(),
            PdfEncryptionFixtures.linearizedPlain,
            PdfEncryptionFixtures.xrefStreamPlain,
        )) {
            val source = CountingSource(bytes)
            assertEquals(PdfEncryption.NOT_ENCRYPTED, PdfEncryption.status(source))
            assertEquals("one open, to scan; a PdfBox load would open it again", 1, source.opens)
        }
    }

    @Test
    fun `linearized and xref-stream user-password pdfs prompt`() {
        for (bytes in listOf(PdfEncryptionFixtures.linearizedUserPassword, PdfEncryptionFixtures.xrefStreamUserPassword)) {
            val source = CountingSource(bytes)
            assertEquals(PdfEncryption.USER_PASSWORD, PdfEncryption.status(source))
            assertEquals("scan once, then the authoritative check once", 2, source.opens)
        }
    }

    @Test
    fun `linearized and xref-stream owner-only pdfs open without a prompt`() {
        for (bytes in listOf(PdfEncryptionFixtures.linearizedOwnerOnly, PdfEncryptionFixtures.xrefStreamOwnerOnly)) {
            assertEquals(PdfEncryption.OWNER_ONLY, PdfEncryption.status(factory(bytes)))
        }
    }

    @Test
    fun `pdfbox written encrypted files are still recognised through the screen`() {
        assertEquals(PdfEncryption.OWNER_ONLY, PdfEncryption.status(factory(protectedPdf("owner-secret", ""))))
        assertEquals(PdfEncryption.USER_PASSWORD, PdfEncryption.status(factory(protectedPdf("owner-secret", "user-secret"))))
    }

    @Test
    fun `the screen and the authoritative check agree on every fixture`() {
        val all = listOf(
            plainPdf(),
            protectedPdf("owner-secret", ""),
            protectedPdf("owner-secret", "user-secret"),
            PdfEncryptionFixtures.linearizedPlain,
            PdfEncryptionFixtures.xrefStreamPlain,
            PdfEncryptionFixtures.linearizedUserPassword,
            PdfEncryptionFixtures.xrefStreamUserPassword,
            PdfEncryptionFixtures.linearizedOwnerOnly,
            PdfEncryptionFixtures.xrefStreamOwnerOnly,
        )
        for (bytes in all) {
            assertEquals(PdfEncryption.authoritativeStatus(factory(bytes)), PdfEncryption.status(factory(bytes)))
        }
    }

    @Test
    fun `the token in page text is a false positive the authoritative check resolves`() {
        val bytes = plainPdf() + "\n% mentions /Encrypt in a comment\n".toByteArray()
        val source = CountingSource(bytes)

        assertEquals(PdfEncryption.NOT_ENCRYPTED, PdfEncryption.status(source))
        assertEquals(2, source.opens)
    }

    @Test
    fun `a token split across the scan chunks is still found`() {
        val token = "/Encrypt".toByteArray()
        // Chunks are 64 KiB; park the token so its first half ends one chunk and its second begins the next.
        for (offset in listOf(64 * 1024 - 1, 64 * 1024 - 4, 64 * 1024 - 7, 64 * 1024, 3 * 64 * 1024 - 3)) {
            val bytes = ByteArray(offset + token.size + 10) { 'a'.code.toByte() }
            token.copyInto(bytes, offset)
            assertTrue("token at $offset", PdfEncryption.mentionsEncryption(factory(bytes)))
        }
    }

    @Test
    fun `a big file with no token is clear and a near miss does not match`() {
        val filler = ByteArray(300_000) { 'x'.code.toByte() }
        assertFalse(PdfEncryption.mentionsEncryption(factory(filler)))
        val near = "/Encryp /encrypt Encrypt/ /Encryp".toByteArray()
        assertFalse(PdfEncryption.mentionsEncryption(factory(near)))
    }

    @Test
    fun `a scan past its limit defers to the authoritative check instead of guessing`() {
        val filler = ByteArray(200_000) { 'x'.code.toByte() }
        assertTrue(PdfEncryption.mentionsEncryption(factory(filler), scanLimit = 100_000))
    }

    @Test
    fun `an empty or unreadable stream is not encrypted`() {
        assertFalse(PdfEncryption.mentionsEncryption(factory(ByteArray(0))))
        assertEquals(
            PdfEncryption.NOT_ENCRYPTED,
            PdfEncryption.status { throw IOException("provider went away") },
        )
    }
}
