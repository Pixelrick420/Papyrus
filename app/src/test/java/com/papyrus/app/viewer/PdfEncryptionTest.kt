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
}
