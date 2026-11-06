package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What the Word, OpenDocument and EPUB readers do with an archive that is cut short, names files outside
 * itself, inflates without end, or holds XML that is not XML. The readers' callers catch what these throw,
 * so the contract is the type: an [IOException] or an [XmlPullParserException], never anything else, and
 * never a file written outside the media folder.
 */
class ArchiveRobustnessTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val officeExtractor = OfficeTextExtractor(mediaDir = null, newParserFactory = OfficeFixtures::parser)

    private fun docxBytes(text: String = "hello") =
        OfficeFixtures.docx(OfficeFixtures.docxBody("<w:p><w:r><w:t>$text</w:t></w:r></w:p>"))

    private fun odtBytes(text: String = "hello") =
        OfficeFixtures.odt(OfficeFixtures.odtContent("<text:p>$text</text:p>"))

    private fun epubBytes() = EpubFixtures.epub(listOf(EpubFixtures.chapter("<p>hello</p>")))

    private fun office(bytes: ByteArray, format: DocumentFormat) =
        officeExtractor.extract(OfficeFixtures.streamFactory(bytes), format)

    private fun epub(bytes: ByteArray, limits: EpubLimits = EpubLimits()) =
        EpubExtractor(mediaDir = null, newParserFactory = OfficeFixtures::parser, limits = limits, rasteriser = EpubFixtures.FakeRasteriser())
            .extract(OfficeFixtures.streamFactory(bytes))

    /** Runs [read]; true if it returned, false if it threw one of the two types callers catch, a failure otherwise. */
    private fun survives(label: String, read: () -> Unit): Boolean = try {
        read()
        true
    } catch (_: IOException) {
        false
    } catch (_: XmlPullParserException) {
        false
    } catch (other: Throwable) {
        fail("$label threw ${other::class.java.name}: ${other.message}")
        false
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun texts(blocks: List<OfficeBlock>) = blocks.filterIsInstance<OfficeBlock.Paragraph>().map { it.text }

    // --- Truncated archives ---

    @Test
    fun `a docx cut at any point is an IO error or a result, never another failure`() {
        val bytes = docxBytes()
        for (length in 0 until bytes.size step 5) {
            survives("docx cut at $length") { office(bytes.copyOf(length), DocumentFormat.DOCX) }
        }
    }

    @Test
    fun `an odt cut at any point is an IO error or a result, never another failure`() {
        val bytes = odtBytes()
        for (length in 0 until bytes.size step 5) {
            survives("odt cut at $length") { office(bytes.copyOf(length), DocumentFormat.ODT) }
        }
    }

    @Test
    fun `an epub cut at any point is an IO error or a result, never another failure`() {
        val bytes = epubBytes()
        for (length in 0 until bytes.size step 7) {
            survives("epub cut at $length") { epub(bytes.copyOf(length)) }
        }
    }

    @Test
    fun `a body cut in the middle of its data does not read as a shorter document`() {
        val body = OfficeFixtures.docxBody("<w:p><w:r><w:t>${"words ".repeat(5_000)}</w:t></w:r></w:p>")
        val bytes = OfficeFixtures.docx(body)
        val cut = bytes.copyOf(bytes.size / 2)

        val outcome = runCatching { office(cut, DocumentFormat.DOCX) }

        assertTrue("a half-read body produced ${outcome.getOrNull()}", outcome.isFailure)
    }

    @Test
    fun `an archive that lost its central directory still reads, since the readers walk the entries`() {
        // The signature of the first central directory record. Everything after it is the directory and the end record.
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        for ((bytes, format) in listOf(docxBytes() to DocumentFormat.DOCX, odtBytes() to DocumentFormat.ODT)) {
            val at = (0..bytes.size - 4).first { i -> signature.indices.all { bytes[i + it] == signature[it] } }
            val withoutDirectory = bytes.copyOf(at)

            assertEquals(texts(office(bytes, format)), texts(office(withoutDirectory, format)))
            assertEquals(listOf("hello"), texts(office(withoutDirectory, format)))
        }
    }

    @Test
    fun `an archive with only the end of the directory cut still reads`() {
        val bytes = docxBytes()

        assertEquals(listOf("hello"), texts(office(bytes.copyOf(bytes.size - 10), DocumentFormat.DOCX)))
    }

    @Test
    fun `bytes that are not an archive at all are an IO error`() {
        for (junk in listOf(ByteArray(0), byteArrayOf(1), ByteArray(4_096) { it.toByte() }, "PK".toByteArray(), "not a zip".toByteArray())) {
            for (format in listOf(DocumentFormat.DOCX, DocumentFormat.ODT)) {
                val outcome = runCatching { office(junk, format) }.exceptionOrNull()
                assertTrue("${junk.size} bytes as $format gave $outcome", outcome is IOException)
            }
            val epubOutcome = runCatching { epub(junk) }.exceptionOrNull()
            assertTrue("${junk.size} bytes as epub gave $epubOutcome", epubOutcome is EpubFormatException)
        }
    }

    // --- Entries that name a file outside the archive ---

    private val slipNames = listOf(
        "../document.xml", "../../word/document.xml", "/word/document.xml", "\\word\\document.xml",
        "word/../word/document.xml", "word/../../evil.xml", "C:\\evil.xml", "word/media/../../../escaped.png",
        "content.xml/../../content.xml", "..", "../",
    )

    @Test
    fun `docx entries that climb out are ignored and the real body is read`() {
        val decoy = OfficeFixtures.docxBody("<w:p><w:r><w:t>decoy</w:t></w:r></w:p>").toByteArray()
        val real = OfficeFixtures.docx(
            OfficeFixtures.docxBody("<w:p><w:r><w:t>real</w:t></w:r></w:p>"),
            rawEntries = slipNames.associateWith { decoy },
        )

        assertEquals(listOf("real"), texts(office(real, DocumentFormat.DOCX)))
    }

    @Test
    fun `an odt entry that climbs out is ignored and the real body is read`() {
        val decoy = OfficeFixtures.odtContent("<text:p>decoy</text:p>").toByteArray()
        val real = OfficeFixtures.odt(
            OfficeFixtures.odtContent("<text:p>real</text:p>"),
            extra = slipNames.associateWith { decoy },
        )

        assertEquals(listOf("real"), texts(office(real, DocumentFormat.ODT)))
    }

    @Test
    fun `an entry that climbs out never writes outside the media folder`() {
        val mediaDir = temp.newFolder("media")
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody("""<w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>"""),
            relsXml = OfficeFixtures.docxRels("rId1" to "../../../escaped.png"),
            rawEntries = mapOf("word/../../../escaped.png" to OfficeFixtures.PNG_1X1, "/abs/escaped.png" to OfficeFixtures.PNG_1X1),
        )

        runCatching {
            OfficeTextExtractor(mediaDir, newParserFactory = OfficeFixtures::parser)
                .extract(OfficeFixtures.streamFactory(bytes), DocumentFormat.DOCX)
        }

        assertEquals(listOf("media"), temp.root.listFiles()!!.map { it.name })
        assertTrue(mediaDir.walkTopDown().filter { it.isFile }.all { it.parentFile == mediaDir })
    }

    @Test
    fun `an epub with an entry that climbs out is refused as a whole`() {
        for (name in slipNames.filterNot { it == ".." || it == "../" } + "OEBPS/../../x") {
            val bytes = EpubFixtures.epub(listOf(EpubFixtures.chapter("<p>x</p>")), extra = mapOf(name to byteArrayOf(1)))

            val outcome = runCatching { epub(bytes) }.exceptionOrNull()

            assertTrue("'$name' gave $outcome", outcome is EpubFormatException)
        }
    }

    // --- Inflation without end ---

    @Test
    fun `a body that inflates past its cap fails instead of being parsed from a cut`() {
        val huge = ByteArray(33 * 1024 * 1024)
        val bytes = zip(listOf("word/document.xml" to huge))

        val outcome = runCatching { office(bytes, DocumentFormat.DOCX) }.exceptionOrNull()

        assertTrue("got $outcome", outcome is IOException)
        assertTrue(outcome!!.message!!.contains("too large"))
    }

    @Test
    fun `a formatting part that inflates past its cap is left out and the body still reads`() {
        val bytes = zip(
            listOf(
                "word/styles.xml" to ByteArray(17 * 1024 * 1024),
                "word/document.xml" to OfficeFixtures.docxBody("<w:p><w:r><w:t>kept</w:t></w:r></w:p>").toByteArray(),
            ),
        )

        assertEquals(listOf("kept"), texts(office(bytes, DocumentFormat.DOCX)))
    }

    @Test
    fun `an odt styles part that inflates past its cap is left out and the body still reads`() {
        val bytes = zip(
            listOf(
                "styles.xml" to ByteArray(17 * 1024 * 1024),
                "content.xml" to OfficeFixtures.odtContent("<text:p>kept</text:p>").toByteArray(),
            ),
        )

        assertEquals(listOf("kept"), texts(office(bytes, DocumentFormat.ODT)))
    }

    @Test
    fun `a great many empty entries do not stop the body being read`() {
        val entries = (1..30_000).map { "word/media/img$it.png" to ByteArray(0) } +
            ("word/document.xml" to OfficeFixtures.docxBody("<w:p><w:r><w:t>kept</w:t></w:r></w:p>").toByteArray())

        assertEquals(listOf("kept"), texts(office(zip(entries), DocumentFormat.DOCX)))
    }

    // --- XML that is not XML ---

    private val brokenBodies = listOf(
        "" to "empty",
        "not xml at all" to "plain text",
        "<w:document><w:body><w:p><w:r><w:t>x" to "cut inside a text run",
        "<w:document><w:body><w:p></w:r></w:p></w:body></w:document>" to "mismatched end tags",
        "<w:document><w:body><w:p><w:r><w:t>a\u0001b\u0000c</w:t></w:r></w:p></w:body></w:document>" to "control characters",
        "<w:document><w:body><w:p><w:r><w:t>&bogus; &#xFFFFFF; &#0;</w:t></w:r></w:p></w:body></w:document>" to "bad references",
        "<w:document><w:body><w:p w:val=\"unterminated><w:r><w:t>x</w:t></w:r></w:p></w:body></w:document>" to "unterminated attribute",
        "<?xml version=\"1.0\" encoding=\"nonsense\"?><w:document/>" to "unknown encoding",
        "\uFEFF\uFEFF<w:document/>" to "doubled byte-order mark",
        "<w:document>" + "<w:p>".repeat(5_000) to "deeply nested and never closed",
    )

    @Test
    fun `a docx body that is not well formed is an error of the two kinds callers catch`() {
        for ((body, label) in brokenBodies) {
            val bytes = OfficeFixtures.docx(body)
            survives("docx body: $label") { office(bytes, DocumentFormat.DOCX) }
        }
    }

    @Test
    fun `an odt body that is not well formed is an error of the two kinds callers catch`() {
        for ((body, label) in brokenBodies) {
            val bytes = OfficeFixtures.odt(body)
            survives("odt body: $label") { office(bytes, DocumentFormat.ODT) }
        }
    }

    @Test
    fun `an entity expansion bomb is not expanded`() {
        val laughs = buildString {
            append("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">")
            var previous = "lol"
            for (level in 1..9) {
                append("<!ENTITY lol$level \"${"&$previous;".repeat(10)}\">")
                previous = "lol$level"
            }
            append("]><w:document><w:body><w:p><w:r><w:t>&$previous;</w:t></w:r></w:p></w:body></w:document>")
        }
        val started = System.nanoTime()

        survives("entity bomb") { office(OfficeFixtures.docx(laughs), DocumentFormat.DOCX) }

        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 10_000_000_000L)
    }

    @Test
    fun `a broken styles part does not cost the document its text`() {
        val bytes = OfficeFixtures.docx(
            bodyXml = OfficeFixtures.docxBody("<w:p><w:r><w:t>kept</w:t></w:r></w:p>"),
            rawEntries = mapOf("word/styles.xml" to "<w:styles><w:style".toByteArray()),
        )

        assertEquals(listOf("kept"), texts(office(bytes, DocumentFormat.DOCX)))
    }

    @Test
    fun `an odt with a broken styles part does not cost the document its text`() {
        val bytes = OfficeFixtures.odt(
            OfficeFixtures.odtContent("<text:p>kept</text:p>"),
            extra = mapOf("styles.xml" to "<office:document-styles><style:style".toByteArray()),
        )

        assertEquals(listOf("kept"), texts(office(bytes, DocumentFormat.ODT)))
    }
}
