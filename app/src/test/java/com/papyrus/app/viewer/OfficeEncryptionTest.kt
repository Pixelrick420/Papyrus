package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.util.Base64

/**
 * Fixtures are real, independently produced: msoffcrypto Agile `.docx` and a hand-rolled ODF 1.2 CBC
 * package, since local LibreOffice only emits the 1.3 form this code rejects.
 */
class OfficeEncryptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        Base64.getMimeDecoder().decode(
            checkNotNull(javaClass.getResourceAsStream("/com/papyrus/app/viewer/$name")) {
                "missing test resource $name"
            }.readBytes(),
        )

    private fun extractedText(file: java.io.File, format: DocumentFormat): String {
        val extractor = OfficeTextExtractor(null, newParserFactory = OfficeFixtures::parser)
        val blocks = extractor.extract({ FileInputStream(file) }, format)
        return blocks.filterIsInstance<OfficeBlock.Paragraph>().joinToString("\n") { it.text }
    }

    private fun unsupportedOdf(): ByteArray {
        val manifest = """
            <?xml version="1.0" encoding="UTF-8"?>
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
              <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml">
                <manifest:encryption-data manifest:checksum-type="SHA1/1K" manifest:checksum="AAAA">
                  <manifest:algorithm manifest:algorithm-name="Blowfish CFB" manifest:initialisation-vector="AAAAAAAAAAAAAAAAAAAAAA=="/>
                  <manifest:key-derivation manifest:key-derivation-name="PBKDF2" manifest:salt="AAAAAAAAAAAAAAAAAAAAAA==" manifest:iteration-count="1024"/>
                  <manifest:start-key-generation manifest:start-key-generation-name="SHA1" manifest:key-size="20"/>
                </manifest:encryption-data>
              </manifest:file-entry>
            </manifest:manifest>
        """.trimIndent().toByteArray()
        return OfficeFixtures.odt(OfficeFixtures.odtContent("<text:p>plain</text:p>"), extra = mapOf("META-INF/manifest.xml" to manifest))
    }

    @Test
    fun `a plain docx is not encrypted`() {
        val bytes = OfficeFixtures.docx(OfficeFixtures.docxBody("<w:p/>"))
        assertEquals(OfficeEncryption.Party.NOT_ENCRYPTED, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `a plain odt is not encrypted`() {
        val bytes = OfficeFixtures.odt(OfficeFixtures.odtContent("<text:p>hi</text:p>"))
        assertEquals(OfficeEncryption.Party.NOT_ENCRYPTED, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `garbage is not encrypted so the viewer reports it generically`() {
        val bytes = "not an office file".toByteArray()
        assertEquals(OfficeEncryption.Party.NOT_ENCRYPTED, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
        assertFalse(OfficeEncryption.isEncrypted(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `an agile docx is reported as password protected`() {
        val bytes = resource("agile-encrypted.b64")
        assertEquals(OfficeEncryption.Party.PASSWORD, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `the agile docx decrypts with the right password into readable text`() {
        val bytes = resource("agile-encrypted.b64")
        val target = temp.newFile("unlocked.docx")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        assertTrue(extractedText(target, DocumentFormat.DOCX).contains("Interop secret paragraph."))
    }

    @Test
    fun `a wrong password on the agile docx throws and leaves no plaintext`() {
        val bytes = resource("agile-encrypted.b64")
        val target = temp.newFile("wrong.docx").apply { writeText("stale") }

        var threw = false
        try {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "guess", target, OfficeFixtures::parser)
        } catch (expected: DocumentPasswordException) {
            threw = true
        }

        assertTrue("a wrong password must surface as DocumentPasswordException", threw)
        assertFalse("a failed decrypt must not leave the target file on disk", target.exists())
    }

    @Test
    fun `an odf 1_2 cbc document is reported as password protected`() {
        val bytes = resource("odf12-encrypted.b64")
        assertEquals(OfficeEncryption.Party.PASSWORD, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `the odf document decrypts with the right password into readable text`() {
        val bytes = resource("odf12-encrypted.b64")
        val target = temp.newFile("unlocked.odt")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        assertTrue(extractedText(target, DocumentFormat.ODT).contains("ODF confidential sentence."))
    }

    @Test
    fun `a wrong password on the odf document throws and leaves no plaintext`() {
        val bytes = resource("odf12-encrypted.b64")
        val target = temp.newFile("wrong.odt").apply { writeText("stale") }

        var threw = false
        try {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "guess", target, OfficeFixtures::parser)
        } catch (expected: DocumentPasswordException) {
            threw = true
        }

        assertTrue(threw)
        assertFalse(target.exists())
    }

    @Test
    fun `an unsupported odf scheme is flagged without a pointless prompt`() {
        val bytes = unsupportedOdf()
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
        assertTrue(OfficeEncryption.isEncrypted(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `a non-agile ooxml encryption version is unsupported`() {
        // Standard (4.2) is recognized but not implemented; only Agile (4.4) gets a password prompt.
        val info = byteArrayOf(0x04, 0x00, 0x02, 0x00, 0x40, 0x00, 0x00, 0x00)
        val bytes = DocFixtures.compound(mapOf("EncryptionInfo" to info))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, OfficeEncryption.status(DocFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }
}
