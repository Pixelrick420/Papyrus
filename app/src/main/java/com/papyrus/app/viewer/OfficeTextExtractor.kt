package com.papyrus.app.viewer

import android.util.Xml
import com.papyrus.app.data.DocumentFormat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Dependency-free .docx / .odt extraction (ZIP + XML) via java.util.zip and the platform
 * XmlPullParser; the archive is walked twice so peak memory stays proportional to the body XML.
 */
class OfficeTextExtractor(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String = ::guessMimeType,
    /** Injected because [Xml.newPullParser] is a platform stub that returns null in JVM unit tests. */
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
) {

    /** [openStream] must return a fresh stream on every call, once for the body and once for the
     * media. The caller closes it. [blockBudget] bounds the body parse: the viewer keeps the full
     * [MAX_BLOCKS], while a thumbnail needs only its first few lines. */
    fun extract(
        openStream: () -> InputStream,
        format: DocumentFormat,
        blockBudget: Int = MAX_BLOCKS,
    ): List<OfficeBlock> {
        val bodyEntry = when (format) {
            DocumentFormat.DOCX -> DOCX_BODY
            DocumentFormat.ODT -> ODT_BODY
            else -> throw IOException("Unsupported office format: $format")
        }
        val partNames = if (format == DocumentFormat.DOCX) DOCX_PARTS else ODT_PARTS

        val found = HashMap<String, ByteArray>()
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in partNames) continue
                    // The body is trusted to fit in memory, as it always was; the parts that only add
                    // formatting are bounded, and simply ignored if one is absurdly large.
                    val bytes = if (entry.name == bodyEntry) readEntry(zip) else readPart(zip)
                    if (bytes != null) found[entry.name] = bytes
                }
            }
        }
        val body = found[bodyEntry] ?: throw IOException("$bodyEntry not found: not a valid document")

        val pending = mutableListOf<PendingImage>()
        val blocks = if (format == DocumentFormat.DOCX) {
            parseDocx(body, found, pending, blockBudget)
        } else {
            parseOdt(body, found, pending, blockBudget)
        }

        OfficeMedia(mediaDir, mimeByName).insertImages(openStream, pending, blocks, blockBudget)
        return blocks
    }

    /** Reads one part, or null past [MAX_PART_BYTES]; the next `nextEntry` skips the rest of the entry. */
    private fun readPart(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(MEDIA_COPY_BUFFER)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_PART_BYTES) return null
            out.write(buffer, 0, n)
        }
    }

    /** Reads the body XML part, capped at [MAX_BODY_BYTES]; over the cap throws [IOException] so the
     * document fails rather than parses from a truncated body. */
    private fun readEntry(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(MEDIA_COPY_BUFFER)
        var total = 0
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("document body is too large")
            out.write(buffer, 0, n)
        }
    }

    private fun newParser(stream: InputStream): XmlPullParser =
        newParserFactory().apply {
            // Namespaces stay in the raw name ("a:blip"); the branch table matches on it.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

    /** Reads an optional part; a malformed one is ignored rather than losing the whole document. */
    private fun <T> optionalPart(fallback: T, read: () -> T): T = try {
        read()
    } catch (_: XmlPullParserException) {
        fallback
    } catch (_: IOException) {
        fallback
    }

    /** `rId7 -> word/media/image3.png` for embedded targets and `rId9 -> https://...` for external
     * ones (hyperlinks). Both empty when the rels part is absent. */
    private fun parseRels(xml: ByteArray?): DocxRels {
        if (xml == null || xml.isEmpty()) return DocxRels.EMPTY
        val targets = HashMap<String, String>()
        val links = HashMap<String, String>()
        val p = newParser(xml.inputStream())
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                if (id != null && target != null) {
                    if (p.getAttributeValue(null, "TargetMode") == "External") {
                        links[id] = target
                    } else {
                        targets[id] = resolveDocxTarget(target)
                    }
                }
            }
            event = p.next()
        }
        return DocxRels(targets, links)
    }

    /** Targets are relative to `word/`, though some producers write an absolute `/word/...`. */
    private fun resolveDocxTarget(target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("$DOCX_DIR/") -> target
        else -> "$DOCX_DIR/$target"
    }

    private fun parseDocx(
        body: ByteArray,
        parts: Map<String, ByteArray>,
        pending: MutableList<PendingImage>,
        blockBudget: Int,
    ): MutableList<OfficeBlock> {
        val factory: ParserFactory = { newParser(it) }
        val rels = parseRels(parts[DOCX_RELS])
        val styles = optionalPart(DocxStyles.empty()) { readDocxStyles(parts[DOCX_STYLES], factory) }
        val numbering = optionalPart(DocxNumbering.EMPTY) { readDocxNumbering(parts[DOCX_NUMBERING], factory) }

        val main = DocxBodyParser(factory, styles, ListCounters(numbering), rels, pending, blockBudget)
        main.parse(body)
        val blocks = main.blocks
        if (main.noteRefs.isEmpty() && main.commentRefs.isEmpty()) return blocks

        // Notes and comments live in parts of their own, read with the same machinery as the body and
        // placed after it.
        fun readPart(entry: String): DocxBodyParser? {
            val bytes = parts[entry] ?: return null
            return optionalPart<DocxBodyParser?>(null) {
                DocxBodyParser(factory, styles, ListCounters(numbering), rels, mutableListOf(), blockBudget)
                    .also { it.parse(bytes) }
            }
        }
        val hasNotes = main.noteRefs.isNotEmpty()
        val footnotes = if (hasNotes) readPart(DOCX_FOOTNOTES)?.notes.orEmpty() else emptyMap()
        val endnotes = if (hasNotes) readPart(DOCX_ENDNOTES)?.notes.orEmpty() else emptyMap()
        val items = ArrayList<OfficeBlock>()
        for (wantEndnotes in listOf(false, true)) {
            for (ref in main.noteRefs) {
                if (ref.endnote != wantEndnotes) continue
                val note = (if (wantEndnotes) endnotes else footnotes)[ref.id] ?: continue
                items += OfficeBlock.Note(ref.label, note.text, note.spans)
            }
        }
        appendSection(blocks, items, blockBudget)

        val commentsPart = if (main.commentRefs.isEmpty()) null else readPart(DOCX_COMMENTS)
        val comments = ArrayList<OfficeBlock>()
        for (ref in main.commentRefs) {
            val comment = commentsPart?.notes?.get(ref.id) ?: continue
            comments += OfficeBlock.Note(ref.label, comment.text, comment.spans, commentsPart.noteBylines[ref.id])
        }
        appendSection(blocks, comments, blockBudget)
        return blocks
    }

    /** A rule, then [items]: notes and comments each sit under their own, after the body. */
    private fun appendSection(blocks: MutableList<OfficeBlock>, items: List<OfficeBlock>, blockBudget: Int) {
        if (items.isEmpty() || blocks.size >= blockBudget) return
        blocks += OfficeBlock.Divider
        blocks.addAll(items)
    }

    private fun parseOdt(
        body: ByteArray,
        parts: Map<String, ByteArray>,
        pending: MutableList<PendingImage>,
        blockBudget: Int,
    ): MutableList<OfficeBlock> {
        val factory: ParserFactory = { newParser(it) }
        val sheet = OdfStyles()
        // Named styles first, so an automatic style in content.xml can override one of the same name.
        parts[ODT_STYLES]?.let { bytes -> optionalPart(Unit) { readOdfStyles(bytes, sheet, factory) } }
        optionalPart(Unit) { readOdfStyles(body, sheet, factory) }
        return OdtBodyParser(factory, sheet, pending, blockBudget).parse(body)
    }

    companion object {
        private const val DOCX_DIR = "word"
        private const val DOCX_BODY = "word/document.xml"
        private const val DOCX_RELS = "word/_rels/document.xml.rels"
        private const val DOCX_STYLES = "word/styles.xml"
        private const val DOCX_NUMBERING = "word/numbering.xml"
        private const val DOCX_FOOTNOTES = "word/footnotes.xml"
        private const val DOCX_ENDNOTES = "word/endnotes.xml"
        private const val DOCX_COMMENTS = "word/comments.xml"
        private const val ODT_BODY = "content.xml"
        private const val ODT_STYLES = "styles.xml"

        private val DOCX_PARTS = setOf(
            DOCX_BODY, DOCX_RELS, DOCX_STYLES, DOCX_NUMBERING, DOCX_FOOTNOTES, DOCX_ENDNOTES, DOCX_COMMENTS,
        )
        private val ODT_PARTS = setOf(ODT_BODY, ODT_STYLES)

        const val MAX_BLOCKS = 20_000
        const val MAX_TABLE_ROWS = 2_000
        const val MAX_IMAGES = 200
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_MEDIA_BYTES = 24 * 1024 * 1024

        /** Cap on the body XML part read into memory before parsing. */
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        /** Parts that only add formatting (styles, numbering, notes) are ignored past this size. */
        private const val MAX_PART_BYTES = 16 * 1024 * 1024

        /** Zip-slip guard: media names come from the document, so never trust their path shape. */
        fun sanitiseName(zipName: String): String =
            zipName.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "image" }

        fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "emf", "wmf" -> "image/x-wmf"
            else -> "image/*"
        }
    }
}
