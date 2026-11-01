package com.papyrus.app.e2e

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The bytes of documents too structured to write inline. Each is the smallest file the viewer's reader accepts,
 * built like the unit tests' own fixtures, which is also what keeps them readable in a diff.
 */
internal object Documents {

    /** A PDF with a page for each of [pages], each showing its text in a standard font. */
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

    /** A Word file with [body] as the content of `w:body`. */
    fun docx(body: String): ByteArray = zip(
        "[Content_Types].xml" to DOCX_CONTENT_TYPES,
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>$body</w:body>
            </w:document>""",
    )

    /** A paragraph of a Word file; a [style] of "Heading1" makes it a heading. */
    fun docxParagraph(text: String, style: String? = null): String {
        val properties = style?.let { """<w:pPr><w:pStyle w:val="$it"/></w:pPr>""" }.orEmpty()
        return "<w:p>$properties<w:r><w:t>${text.xmlEscaped()}</w:t></w:r></w:p>"
    }

    fun docxTable(rows: List<List<String>>): String = rows.joinToString(prefix = "<w:tbl>", postfix = "</w:tbl>", separator = "") { row ->
        row.joinToString(prefix = "<w:tr>", postfix = "</w:tr>", separator = "") { cell ->
            "<w:tc>${docxParagraph(cell)}</w:tc>"
        }
    }

    /** A text document with [body] as the content of `office:text`. */
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

    /** One chapter of a book: [href] is relative to the package folder, [body] is the XHTML inside `body`. */
    class Chapter(val href: String, val body: String)

    /** A book of [chapters] in reading order, with a table of contents of ([title], chapter href) entries. */
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

    private const val DOCX_CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="xml" ContentType="application/xml"/>
        </Types>"""
}
