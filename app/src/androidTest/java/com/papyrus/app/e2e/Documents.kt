package com.papyrus.app.e2e

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object Documents {

    fun pdf(vararg pages: String): ByteArray {
        val objects = ArrayList<String>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${3 + it * 2} 0 R" }}] /Count ${pages.size} >>"
        pages.forEachIndexed { index, text ->
            val drawing = "BT /F1 18 Tf 24 340 Td (${text.pdfEscaped()}) Tj ET"
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] /Contents ${4 + index * 2} 0 R " +
                "/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>"
            objects += "<< /Length ${drawing.length} >>\nstream\n$drawing\nendstream"
        }
        val file = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            file.length.also { file.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val crossReference = file.length
        file.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { file.append(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        file.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$crossReference\n%%EOF\n")
        // One byte per character, so the offsets above are byte offsets.
        return file.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun String.pdfEscaped() = replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    fun docx(body: String): ByteArray = zip(
        "[Content_Types].xml" to DOCX_CONTENT_TYPES,
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>$body</w:body>
            </w:document>""",
    )

    fun docxParagraph(text: String, style: String? = null): String {
        val properties = style?.let { """<w:pPr><w:pStyle w:val="$it"/></w:pPr>""" }.orEmpty()
        return "<w:p>$properties<w:r><w:t>${text.xmlEscaped()}</w:t></w:r></w:p>"
    }

    fun docxTable(rows: List<List<String>>): String = rows.joinToString(prefix = "<w:tbl>", postfix = "</w:tbl>", separator = "") { row ->
        row.joinToString(prefix = "<w:tr>", postfix = "</w:tr>", separator = "") { cell ->
            "<w:tc>${docxParagraph(cell)}</w:tc>"
        }
    }

    fun odt(body: String): ByteArray = zip(
        "mimetype" to "application/vnd.oasis.opendocument.text",
        "content.xml" to """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:text>$body</office:text></office:body>
            </office:document-content>""",
    )

    fun odtHeading(text: String, level: Int = 1) =
        """<text:h text:outline-level="$level">${text.xmlEscaped()}</text:h>"""

    fun odtParagraph(text: String) = "<text:p>${text.xmlEscaped()}</text:p>"

    // Declares an encrypted entry in its manifest, which is all the app reads to tell that a file is password protected.
    fun encryptedOdt(): ByteArray = zip(
        "mimetype" to "application/vnd.oasis.opendocument.text",
        "content.xml" to """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body><office:text><text:p>locked</text:p></office:text></office:body>
            </office:document-content>""",
        "META-INF/manifest.xml" to ENCRYPTED_ODF_MANIFEST,
    )

    class Chapter(val href: String, val body: String)

    fun epub(chapters: List<Chapter>, contents: List<Pair<String, String>> = emptyList()): ByteArray {
        val manifest = chapters.mapIndexed { index, chapter ->
            """<item id="c$index" href="${chapter.href}" media-type="application/xhtml+xml"/>"""
        }.joinToString("") + """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val toc = contents.joinToString("") { (title, href) -> """<li><a href="$href">${title.xmlEscaped()}</a></li>""" }
        val entries = ArrayList<Pair<String, String>>()
        entries += "mimetype" to "application/epub+zip"
        entries += "META-INF/container.xml" to """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        entries += "OEBPS/content.opf" to """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata>
              <manifest>$manifest</manifest>
              <spine>$spine</spine>
            </package>"""
        entries += "OEBPS/nav.xhtml" to xhtml("""<nav epub:type="toc"><ol>$toc</ol></nav>""")
        chapters.forEach { entries += "OEBPS/${it.href}" to xhtml(it.body) }
        return zip(*entries.toTypedArray())
    }

    private fun xhtml(body: String) = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><title>t</title></head><body>$body</body>
        </html>"""

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { archive ->
            for ((name, content) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(content.trimIndent().toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun String.xmlEscaped() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val ENCRYPTED_ODF_MANIFEST = """<?xml version="1.0" encoding="UTF-8"?>
        <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">
          <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml">
            <manifest:encryption-data manifest:checksum-type="SHA1/1K" manifest:checksum="AAAAAAAAAAAAAAAAAAAAAAAAAAA=">
              <manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="AAAAAAAAAAAAAAAAAAAAAA=="/>
              <manifest:key-derivation manifest:key-derivation-name="PBKDF2" manifest:key-size="32" manifest:iteration-count="100000" manifest:salt="AAAAAAAAAAAAAAAAAAAAAA=="/>
              <manifest:start-key-generation manifest:start-key-generation-name="SHA256" manifest:key-size="32"/>
            </manifest:encryption-data>
          </manifest:file-entry>
        </manifest:manifest>"""

    private const val DOCX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="xml" ContentType="application/xml"/>
        </Types>"""
}
