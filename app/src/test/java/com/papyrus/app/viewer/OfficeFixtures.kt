package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archives are built in memory: a binary fixture cannot be reviewed in a diff. */
internal object OfficeFixtures {

    /** A minimal valid 1x1 PNG, for the media paths. */
    val PNG_1X1: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
        0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
        0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    fun docx(
        bodyXml: String,
        relsXml: String? = null,
        media: Map<String, ByteArray> = emptyMap(),
        rawEntries: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val entries = buildMap {
            put("[Content_Types].xml", CONTENT_TYPES.toByteArray())
            put("word/document.xml", bodyXml.toByteArray())
            if (relsXml != null) put("word/_rels/document.xml.rels", relsXml.toByteArray())
            media.forEach { (name, bytes) -> put("word/media/$name", bytes) }
            putAll(rawEntries)
        }
        return zip(entries)
    }

    /** A bare archive with no body part, for the "not a valid document" path. */
    fun zipOf(entries: Map<String, ByteArray>): ByteArray = zip(entries)

    fun odt(contentXml: String, media: Map<String, ByteArray> = emptyMap()): ByteArray {
        val entries = buildMap {
            put("mimetype", "application/vnd.oasis.opendocument.text".toByteArray())
            put("content.xml", contentXml.toByteArray())
            media.forEach { (name, bytes) -> put("Pictures/$name", bytes) }
        }
        return zip(entries)
    }

    /** Wraps [body] in a `w:document` shell, so tests only write the interesting part. */
    fun docxBody(body: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:v="urn:schemas-microsoft-com:vml">
             <w:body>$body</w:body>
           </w:document>""".trimIndent()

    fun docxRels(vararg entries: Pair<String, String>) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${
            entries.joinToString("") { (id, target) ->
                """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="$target"/>"""
            }
           }</Relationships>""".trimIndent()

    fun odtContent(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <office:document-content
               xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
               xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
               xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
               xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
               xmlns:xlink="http://www.w3.org/1999/xlink">
             <office:body><office:text>$body</office:text></office:body>
           </office:document-content>""".trimIndent()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="xml" ContentType="application/xml"/>
          <Default Extension="png" ContentType="image/png"/>
        </Types>"""

    /** A stream factory for [OfficeTextExtractor.extract], which opens the archive more than once. */
    fun streamFactory(bytes: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(bytes) }

    /** The kxml2 parser the injected factory returns. */
    fun parser(): XmlPullParser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
}
