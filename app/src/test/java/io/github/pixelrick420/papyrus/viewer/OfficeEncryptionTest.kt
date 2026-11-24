package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.data.DocumentFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

// Fixtures are real and independently produced: msoffcrypto Agile `.docx` and a hand-rolled ODF 1.2 CBC package.
class OfficeEncryptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        Base64.getMimeDecoder().decode(
            checkNotNull(javaClass.getResourceAsStream("/io/github/pixelrick420/papyrus/viewer/$name")) {
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

    private fun odfWithExtraEntry(name: String, bytes: ByteArray, first: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun ZipOutputStream.add(entryName: String, data: ByteArray) {
            putNextEntry(ZipEntry(entryName))
            write(data)
            closeEntry()
        }
        ZipInputStream(ByteArrayInputStream(resource("odf12-encrypted.b64"))).use { zip ->
            ZipOutputStream(out).use { rewritten ->
                if (first) rewritten.add(name, bytes)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    rewritten.add(entry.name, zip.readBytes())
                }
                if (!first) rewritten.add(name, bytes)
            }
        }
        return out.toByteArray()
    }

    private fun odfStatus(bytes: ByteArray) =
        OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser)

    private fun withFirstZipEntry(source: ByteArray, name: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(source)).use { zip ->
            ZipOutputStream(out).use { rewritten ->
                rewritten.putNextEntry(ZipEntry(name))
                rewritten.write(data)
                rewritten.closeEntry()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    rewritten.putNextEntry(ZipEntry(entry.name))
                    rewritten.write(zip.readBytes())
                    rewritten.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    private class CountingOpens(private val bytes: ByteArray) {
        var opens = 0
            private set

        val factory: () -> java.io.InputStream = {
            opens++
            ByteArrayInputStream(bytes)
        }
    }

    private class EncPart(val path: String, val compressed: ByteArray, val iv: ByteArray = ByteArray(16) { 1 })

    private fun rawDeflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(data) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun highlyCompressibleZeros(total: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val block = ByteArray(64 * 1024)
        try {
            DeflaterOutputStream(out, deflater, block.size).use { sink ->
                var left = total
                while (left > 0) {
                    val n = minOf(left, block.size)
                    sink.write(block, 0, n)
                    left -= n
                }
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun encryptedOdf(
        parts: List<EncPart>,
        password: String = "secret123",
        iterations: Int = 1000,
        startKeyName: String? = "http://www.w3.org/2000/09/xmldsig#sha256",
        salt: ByteArray = ByteArray(16) { 2 },
    ): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val start = if (startKeyName == null || startKeyName.contains("sha1", ignoreCase = true)) {
            MessageDigest.getInstance("SHA-1").digest(passwordBytes)
        } else {
            MessageDigest.getInstance("SHA-256").digest(passwordBytes)
        }
        val key = OfficeEncryption.pbkdf2Sha1(start, salt, iterations, 32)
        val manifest = StringBuilder(
            """<?xml version="1.0" encoding="UTF-8"?>
            <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">""".trimIndent(),
        )
        parts.forEach { part ->
            val digest = MessageDigest.getInstance("SHA-1")
                .digest(part.compressed.copyOf(minOf(1024, part.compressed.size)))
            val checksum = Base64.getEncoder().encodeToString(digest)
            val startKey = if (startKeyName == null) "" else
                """<manifest:start-key-generation manifest:start-key-generation-name="$startKeyName" manifest:key-size="32"/>"""
            manifest.append("""<manifest:file-entry manifest:full-path="${part.path}" manifest:media-type="text/xml">""")
            manifest.append(
                """<manifest:encryption-data manifest:checksum-type="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha1-1k" manifest:checksum="$checksum">""",
            )
            manifest.append(
                """<manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="${Base64.getEncoder().encodeToString(part.iv)}"/>""",
            )
            manifest.append(
                """<manifest:key-derivation manifest:key-derivation-name="PBKDF2" manifest:salt="${Base64.getEncoder().encodeToString(salt)}" manifest:iteration-count="$iterations" manifest:key-size="32"/>""",
            )
            manifest.append(startKey)
            manifest.append("</manifest:encryption-data></manifest:file-entry>")
        }
        manifest.append("</manifest:manifest>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/vnd.oasis.opendocument.text".toByteArray()
            val crc = CRC32().also { it.update(mime) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                this.crc = crc.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/manifest.xml"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            parts.forEach { part ->
                val pad = 16 - part.compressed.size % 16
                val padded = part.compressed + ByteArray(pad) { pad.toByte() }
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(part.iv))
                zip.putNextEntry(ZipEntry(part.path))
                zip.write(cipher.doFinal(padded))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun odfWithManifestLast(): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(resource("odf12-encrypted.b64"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { rewritten ->
            entries.filter { it.first != "META-INF/manifest.xml" }.forEach { (name, bytes) ->
                rewritten.putNextEntry(ZipEntry(name))
                rewritten.write(bytes)
                rewritten.closeEntry()
            }
            val manifest = entries.first { it.first == "META-INF/manifest.xml" }
            rewritten.putNextEntry(ZipEntry(manifest.first))
            rewritten.write(manifest.second)
            rewritten.closeEntry()
        }
        return out.toByteArray()
    }

    private fun odfWithMimetypeLast(): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(resource("odf12-encrypted.b64"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes()
            }
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { rewritten ->
            entries.filter { it.first != "mimetype" }.forEach { (name, bytes) ->
                rewritten.putNextEntry(ZipEntry(name))
                rewritten.write(bytes)
                rewritten.closeEntry()
            }
            val mimetype = entries.first { it.first == "mimetype" }
            rewritten.putNextEntry(ZipEntry(mimetype.first))
            rewritten.write(mimetype.second)
            rewritten.closeEntry()
        }
        return out.toByteArray()
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
    fun `a plain pptx is not encrypted`() {
        val bytes = OfficeFixtures.zipOf(
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "ppt/presentation.xml" to "<p:presentation xmlns:p='http://schemas.openxmlformats.org/presentationml/2006/main'/>".toByteArray(),
            ),
        )
        assertEquals(
            OfficeEncryption.Party.NOT_ENCRYPTED,
            OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser),
        )
    }

    @Test
    fun `an agile pptx is reported as password protected`() {
        val bytes = resource("pptx-agile-encrypted.b64")
        assertEquals(
            OfficeEncryption.Party.PASSWORD,
            OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser),
        )
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
    fun `a bomb ahead of the odf manifest is not walked to find it`() {
        val bytes = odfWithExtraEntry("Pictures/bomb.bin", ByteArray(8 * 1024 * 1024), first = true)

        // The bomb stops the manifest scan: an error, never a password prompt.
        assertEquals(OfficeEncryption.Party.ERROR, odfStatus(bytes))
    }

    @Test
    fun `decrypting an odf whose archive holds a bomb after the manifest is refused instead of inflated`() {
        val bytes = odfWithExtraEntry("Pictures/bomb.bin", ByteArray(8 * 1024 * 1024), first = false)
        val target = temp.newFile("bomb.odt")

        val failure = runCatching {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is IOException)
        assertFalse("a password prompt would be pointless", failure is DocumentPasswordException)
        assertEquals("implausible compression", failure?.message)
        assertFalse(target.exists())
    }

    @Test
    fun `a non-agile ooxml encryption version is unsupported`() {
        // Standard (4.2) is recognized but not implemented; only Agile (4.4) gets a password prompt.
        val info = byteArrayOf(0x04, 0x00, 0x02, 0x00, 0x40, 0x00, 0x00, 0x00)
        val bytes = DocFixtures.compound(mapOf("EncryptionInfo" to info))
        assertEquals(OfficeEncryption.Party.UNSUPPORTED, OfficeEncryption.status(DocFixtures.streamFactory(bytes), OfficeFixtures::parser))
    }

    @Test
    fun `agile key derivation checks every interval`() {
        var checks = 0

        OfficeEncryption.deriveIteratedHash("secret", ByteArray(8), "SHA1", 20_000) { checks++ }

        assertEquals(5, checks)
    }

    @Test
    fun `agile key derivation stops when the caller cancels`() {
        var checks = 0

        val failure = assertThrows(CancellationException::class.java) {
            OfficeEncryption.deriveIteratedHash("secret", ByteArray(8), "SHA1", 5_000_000) {
                checks++
                if (checks == 3) throw CancellationException("cancelled")
            }
        }

        assertEquals(3, checks)
        assertEquals("cancelled", failure.message)
    }

    @Test
    fun `pbkdf2 checks every interval`() {
        var checks = 0

        OfficeEncryption.pbkdf2Sha1(ByteArray(8), ByteArray(8), 20_000, 20) { checks++ }

        assertEquals(4, checks)
    }

    @Test
    fun `pbkdf2 stops when the caller cancels`() {
        var checks = 0

        val failure = assertThrows(CancellationException::class.java) {
            OfficeEncryption.pbkdf2Sha1(ByteArray(8), ByteArray(8), 1_000_000, 20) {
                checks++
                if (checks == 2) throw CancellationException("cancelled")
            }
        }

        assertEquals(2, checks)
        assertEquals("cancelled", failure.message)
    }

    @Test
    fun `a short digest is padded with the spec byte for a block key`() {
        val hash = "hash".toByteArray()
        val blockKey = byteArrayOf(1, 2, 3)
        val digest = MessageDigest.getInstance("SHA-1").digest(hash + blockKey)

        val derived = OfficeEncryption.deriveKey(hash, blockKey, "SHA1", 256)

        assertEquals(32, derived.size)
        assertArrayEquals(digest, derived.copyOf(digest.size))
        assertTrue(derived.copyOfRange(digest.size, derived.size).all { it == 0x36.toByte() })
    }

    @Test
    fun `a digest that already fits is only truncated`() {
        val hash = "hash".toByteArray()
        val blockKey = byteArrayOf(1, 2, 3)
        val digest = MessageDigest.getInstance("SHA-512").digest(hash + blockKey)

        assertArrayEquals(digest.copyOf(32), OfficeEncryption.deriveKey(hash, blockKey, "SHA512", 256))
    }

    @Test
    fun `an ooxml first entry keeps a later odf manifest from prompting`() {
        val bytes = withFirstZipEntry(unsupportedOdf(), "[Content_Types].xml", "<Types/>".toByteArray())

        assertEquals(
            OfficeEncryption.Party.NOT_ENCRYPTED,
            OfficeEncryption.status(OfficeFixtures.streamFactory(bytes), OfficeFixtures::parser),
        )
    }

    @Test
    fun `a supplied length skips the probing open`() {
        val bytes = resource("agile-encrypted.b64")
        val probed = CountingOpens(bytes)
        val known = CountingOpens(bytes)

        assertEquals(OfficeEncryption.Party.PASSWORD, OfficeEncryption.status(probed.factory, OfficeFixtures::parser))
        assertEquals(
            OfficeEncryption.Party.PASSWORD,
            OfficeEncryption.status(known.factory, OfficeFixtures::parser, knownLength = bytes.size.toLong()),
        )
        assertEquals(probed.opens, known.opens + 1)
    }

    @Test
    fun `the agile docx still decrypts when the caller supplies its length`() {
        val bytes = resource("agile-encrypted.b64")
        val target = temp.newFile("known-length.docx")

        OfficeEncryption.decrypt(
            OfficeFixtures.streamFactory(bytes),
            "secret123",
            target,
            OfficeFixtures::parser,
            knownLength = bytes.size.toLong(),
        )

        assertTrue(extractedText(target, DocumentFormat.DOCX).contains("Interop secret paragraph."))
    }

    @Test
    fun `an omitted start key generation name derives the start key with sha1`() {
        val content = OfficeFixtures.odtContent("<text:p>sha1 fallback</text:p>").toByteArray()
        val bytes = encryptedOdf(listOf(EncPart("content.xml", rawDeflate(content))), startKeyName = null)
        val target = temp.newFile("sha1-fallback.odt")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        assertTrue(extractedText(target, DocumentFormat.ODT).contains("sha1 fallback"))
    }

    @Test
    fun `a zlib wrapped odf entry still decrypts`() {
        val content = OfficeFixtures.odtContent("<text:p>wrapped deflate</text:p>").toByteArray()
        val wrapped = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(content) } }.toByteArray()
        val bytes = encryptedOdf(listOf(EncPart("content.xml", wrapped)))
        val target = temp.newFile("wrapped.odt")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        assertTrue(extractedText(target, DocumentFormat.ODT).contains("wrapped deflate"))
    }

    @Test
    fun `a highly compressible encrypted entry decrypts within the cap`() {
        val text = "x".repeat(1024 * 1024)
        val content = OfficeFixtures.odtContent("<text:p>$text</text:p>").toByteArray()
        val bytes = encryptedOdf(listOf(EncPart("content.xml", rawDeflate(content))))
        val target = temp.newFile("compressible.odt")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        ZipInputStream(FileInputStream(target)).use { zip ->
            var decrypted: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "content.xml") decrypted = String(zip.readBytes(), Charsets.UTF_8)
            }
            assertTrue(decrypted!!.contains(text))
        }
    }

    @Test(timeout = 20_000)
    fun `an entry that inflates past the cap is refused`() {
        val bytes = encryptedOdf(listOf(EncPart("content.xml", highlyCompressibleZeros(65 * 1024 * 1024))))
        val target = temp.newFile("inflate-bomb.odt")

        val failure = runCatching {
            OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)
        }.exceptionOrNull()

        assertTrue("got $failure", failure is IOException)
        assertEquals("entry is too large", failure?.message)
        assertFalse(target.exists())
    }

    @Test
    fun `decrypt writes mimetype first uncompressed and without extra header data`() {
        val bytes = odfWithMimetypeLast()
        val target = temp.newFile("mimetype-first.odt")

        OfficeEncryption.decrypt(OfficeFixtures.streamFactory(bytes), "secret123", target, OfficeFixtures::parser)

        ZipInputStream(FileInputStream(target)).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            assertTrue(first.extra?.isEmpty() != false)
            assertEquals("application/vnd.oasis.opendocument.text", String(zip.readBytes(), Charsets.UTF_8))
            assertEquals("META-INF/manifest.xml", zip.nextEntry!!.name)
            assertEquals("content.xml", zip.nextEntry!!.name)
        }
    }

    @Test(timeout = 20_000)
    fun `a shared odf key derivation is reused across encrypted entries`() {
        val content = OfficeFixtures.odtContent("<text:p>shared key</text:p>").toByteArray()
        val single = encryptedOdf(listOf(EncPart("content.xml", rawDeflate(content))), iterations = 20_000)
        val shared = encryptedOdf(
            listOf(
                EncPart("content.xml", rawDeflate(content)),
                EncPart("styles.xml", rawDeflate(content), iv = ByteArray(16) { 3 }),
            ),
            iterations = 20_000,
        )
        var singleChecks = 0
        var sharedChecks = 0

        OfficeEncryption.decrypt(
            OfficeFixtures.streamFactory(single), "secret123", temp.newFile("single.odt"), OfficeFixtures::parser,
            ensureActive = { singleChecks++ },
        )
        OfficeEncryption.decrypt(
            OfficeFixtures.streamFactory(shared), "secret123", temp.newFile("shared.odt"), OfficeFixtures::parser,
            ensureActive = { sharedChecks++ },
        )

        assertTrue("expected one derivation: $singleChecks vs $sharedChecks", sharedChecks <= singleChecks + 4)
    }

    @Test
    fun `a seekable file reads the manifest without inflating earlier entries`() {
        val bytes = odfWithExtraEntry("Pictures/bomb.bin", ByteArray(8 * 1024 * 1024), first = true)
        val file = temp.newFile("seek.odt").apply { writeBytes(bytes) }

        assertEquals(OfficeEncryption.Party.PASSWORD, OfficeEncryption.status(file, OfficeFixtures::parser))
        assertTrue(OfficeEncryption.isEncrypted(file, OfficeFixtures::parser))
    }

    @Test
    fun `an odf manifest written last is found through the central directory of a file`() {
        val file = temp.newFile("manifest-last.odt").apply { writeBytes(odfWithManifestLast()) }

        assertEquals(OfficeEncryption.Party.PASSWORD, OfficeEncryption.status(file, OfficeFixtures::parser))
        val target = temp.newFile("manifest-last-out.odt")
        OfficeEncryption.decrypt(file, "secret123", target, OfficeFixtures::parser)
        assertTrue(extractedText(target, DocumentFormat.ODT).contains("ODF confidential sentence."))
    }

    @Test
    fun `an ooxml first entry also short circuits the seekable file probe`() {
        val bytes = withFirstZipEntry(unsupportedOdf(), "[Content_Types].xml", "<Types/>".toByteArray())
        val file = temp.newFile("ooxml-first.odt").apply { writeBytes(bytes) }

        assertEquals(OfficeEncryption.Party.NOT_ENCRYPTED, OfficeEncryption.status(file, OfficeFixtures::parser))
    }

    @Test
    fun `localSourceFile resolves only existing file uris`() {
        val file = temp.newFile("source.odt")
        val missing = java.io.File(temp.root, "missing.odt")

        assertEquals(file, localSourceFile(file.toURI().toString()))
        assertNull(localSourceFile("content://com.example.provider/document/1"))
        assertNull(localSourceFile("content://${file.absolutePath}"))
        assertNull(localSourceFile("not a uri with spaces"))
        assertNull(localSourceFile(missing.toURI().toString()))
    }
}
