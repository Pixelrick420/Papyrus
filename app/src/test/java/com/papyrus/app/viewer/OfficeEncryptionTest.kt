package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

    /** The real ODF 1.2 fixture with only the two numbers its manifest controls for key derivation changed. */
    private fun odfWithKeyDerivation(iterationCount: Int, keySize: Int = 32): ByteArray {
        val original = "manifest:iteration-count=\"100000\" manifest:key-size=\"32\""
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(resource("odf12-encrypted.b64"))).use { zip ->
            ZipOutputStream(out).use { rewritten ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    var data = zip.readBytes()
                    if (entry.name == "META-INF/manifest.xml") {
                        val manifest = data.toString(Charsets.UTF_8)
                        check(original in manifest) { "fixture manifest changed" }
                        data = manifest
                            .replace(original, "manifest:iteration-count=\"$iterationCount\" manifest:key-size=\"$keySize\"")
                            .toByteArray()
                    }
                    rewritten.putNextEntry(ZipEntry(entry.name))
                    rewritten.write(data)
                    rewritten.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private fun odfStatus(bytes: ByteArray) =
        OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser)

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
    fun `an odf iteration count past the ceiling is flagged without a prompt and one at it is not`() {
        assertEquals(OfficeEncryption.Party.PASSWORD, odfStatus(odfWithKeyDerivation(iterationCount = 1_000_000)))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, odfStatus(odfWithKeyDerivation(iterationCount = 1_000_001)))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, odfStatus(odfWithKeyDerivation(iterationCount = Int.MAX_VALUE)))
    }

    @Test
    fun `an odf key size other than aes-256's is flagged without a prompt`() {
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, odfStatus(odfWithKeyDerivation(100_000, keySize = 16)))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, odfStatus(odfWithKeyDerivation(100_000, keySize = 1_000_000_000)))
    }

    @Test(timeout = 10_000)
    fun `decrypting an odf with a hostile iteration count fails at once instead of spinning`() {
        val bytes = odfWithKeyDerivation(iterationCount = Int.MAX_VALUE)
        val target = temp.newFile("spin.odt")

        val failure = runCatching {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse("a password prompt would be pointless", failure is DocumentPasswordException)
        assertFalse(target.exists())
    }

    @Test(timeout = 10_000)
    fun `decrypting an odf with a hostile key size fails at once instead of deriving it`() {
        val bytes = odfWithKeyDerivation(iterationCount = 100_000, keySize = 1_000_000_000)
        val target = temp.newFile("keysize.odt")

        val failure = runCatching {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure is DocumentPasswordException)
        assertFalse(target.exists())
    }

    @Test
    fun `a non-agile ooxml encryption version is unsupported`() {
        // Standard (4.2) is recognized but not implemented; only Agile (4.4) gets a password prompt.
        val info = byteArrayOf(0x04, 0x00, 0x02, 0x00, 0x40, 0x00, 0x00, 0x00)
        val bytes = DocFixtures.compound(mapOf("EncryptionInfo" to info))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, OfficeEncryption.status(DocFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }
}
