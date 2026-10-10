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
 * Dependency-free extraction for .docx / .odt (ZIP + XML) via java.util.zip and the platform
 * XmlPullParser; the archive is walked twice so peak memory stays proportional to the body XML.
 */
class OfficeTextExtractor(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String = ::guessMimeType,
    /** Injected because [Xml.newPullParser] is a platform stub, so a JVM unit test would get null instead of a parser. */
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
) {

    /** [openStream] must return a fresh stream on every call, once for the body and once for the media. The caller closes it. */
    fun extract(openStream: () -> InputStream, format: DocumentFormat): List<OfficeBlock> {
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
        val blocks = if (format == DocumentFormat.DOCX) parseDocx(body, found, pending) else parseOdt(body, found, pending)

        if (pending.isNotEmpty() && mediaDir != null) {
            val wanted = pending.mapTo(HashSet()) { it.zipName }
            val budget = MediaBudget()
            // zipName -> the file it was written to, so one entry referenced from several places inserts one
            // block per *reference* while its bytes are read and written once.
            val written = HashMap<String, File>()
            readMedia(openStream, wanted, budget) { zipName, bytes ->
                val file = writeMedia(zipName, bytes) ?: return@readMedia
                written[zipName] = file
            }
            // Insert at the recorded position so images land where they appeared rather than being appended.
            var inserted = 0
            pending.forEach { image ->
                // A dangling relationship or a budget rejection lands here as null; the text still renders.
                val file = written[image.zipName] ?: return@forEach
                if (blocks.size >= MAX_BLOCKS) return@forEach
                // `index` was recorded before any image went in, so each image already inserted pushed
                // it down by one; without the offset, images drifted up and clustered near the first.
                val block = OfficeBlock.Image(file, mimeByName(image.zipName), image.widthDp, image.altText)
                blocks.add((image.index + inserted).coerceIn(0, blocks.size), block)
                inserted++
            }
        }
        return blocks
    }

    /** Reads one part, or null once it passes [MAX_PART_BYTES]; the rest of the entry is skipped by the next `nextEntry`. */
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

    /**
     * Byte budget so photographs cannot fill the disk or heap. Reserve then consume: [canReserve] is
     * the cheap pre-check, [consume] the authoritative charge.
     */
    private class MediaBudget {
        private var remaining = MAX_TOTAL_MEDIA_BYTES
        private var count = 0

        /** True when an entry of [declaredSize] (-1 when unknown) could plausibly be accepted. */
        fun canReserve(declaredSize: Long): Boolean {
            if (count >= MAX_IMAGES) return false
            if (declaredSize > MAX_IMAGE_BYTES) return false
            if (declaredSize > remaining) return false
            return true
        }

        fun consume(size: Int): Boolean {
            if (size > MAX_IMAGE_BYTES || size > remaining) return false
            remaining -= size
            count++
            return true
        }
    }

    private fun writeMedia(zipName: String, bytes: ByteArray): File? {
        val dir = mediaDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        // Two entries in one archive can sanitise to the same name; disambiguate rather than clobber.
        val target = File(dir, sanitiseName(zipName))
        if (!target.exists()) return target.writeOrNull(bytes)
        val base = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "bin" }
        var suffix = 1
        while (true) {
            val candidate = File(dir, "$base-$suffix.$ext")
            if (!candidate.exists()) return candidate.writeOrNull(bytes)
            suffix++
        }
    }

    private fun File.writeOrNull(bytes: ByteArray): File? = try {
        writeBytes(bytes)
        this
    } catch (_: IOException) {
        null
    }

    /**
     * Streams wanted entries to [sink], charging the budget as each arrives rather than buffering
     * them all. The per-entry cap counts bytes, not `entry.size`, which can be -1 when streamed.
     */
    private fun readMedia(
        openStream: () -> InputStream,
        wanted: Set<String>,
        budget: MediaBudget,
        sink: (zipName: String, bytes: ByteArray) -> Unit,
    ) {
        if (wanted.isEmpty()) return
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in wanted) continue
                    // Reserve before reading: an entry the budget will never accept must not be decoded at all.
                    if (!budget.canReserve(entry.size)) continue
                    val bytes = readBounded(zip, entry.size, buffer) ?: continue
                    if (!budget.consume(bytes.size)) continue
                    sink(entry.name, bytes)
                }
            }
        }
    }

    /**
     * Reads the body XML part, capped at [MAX_BODY_BYTES]; over the cap throws [IOException] so the
     * document fails rather than parses from a truncated body.
     */
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

    /** Reads one entry, capped at the smaller of its declared size and [MAX_IMAGE_BYTES]; null if it exceeds that. */
    private fun readBounded(zip: ZipInputStream, declaredSize: Long, buffer: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(
            if (declaredSize in 1..MAX_IMAGE_BYTES) declaredSize.toInt() else MEDIA_COPY_BUFFER,
        )
        while (true) {
            // A sized entry that yields more than it declared is rejected, not truncated into a corrupt image.
            val overBudget = out.size() >= MAX_IMAGE_BYTES
            val overDeclared = declaredSize >= 0 && out.size() >= declaredSize
            if (overBudget) return null
            if (overDeclared) return out.toByteArray()
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
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

    /**
     * `rId7 -> word/media/image3.png` for embedded targets and `rId9 -> https://...` for external ones
     * (hyperlinks). Both empty when the rels part is absent.
     */
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

    /** Relationship targets are relative to `word/`, though some producers write an absolute `/word/...`. */
    private fun resolveDocxTarget(target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("$DOCX_DIR/") -> target
        else -> "$DOCX_DIR/$target"
    }

    private fun parseDocx(
        body: ByteArray,
        parts: Map<String, ByteArray>,
        pending: MutableList<PendingImage>,
    ): MutableList<OfficeBlock> {
        val factory: ParserFactory = { newParser(it) }
        val rels = parseRels(parts[DOCX_RELS])
        val styles = optionalPart(DocxStyles.empty()) { readDocxStyles(parts[DOCX_STYLES], factory) }
        val numbering = optionalPart(DocxNumbering.EMPTY) { readDocxNumbering(parts[DOCX_NUMBERING], factory) }

        val main = DocxBodyParser(factory, styles, ListCounters(numbering), rels, pending)
        main.parse(body)
        val blocks = main.blocks
        if (main.noteRefs.isEmpty()) return blocks

        // Notes live in parts of their own, read with the same machinery as the body and placed after it.
        fun readNotes(entry: String): Map<String, RichSnapshot> {
            val bytes = parts[entry] ?: return emptyMap()
            return optionalPart<Map<String, RichSnapshot>>(emptyMap()) {
                val reader = DocxBodyParser(factory, styles, ListCounters(numbering), rels, mutableListOf())
                reader.parse(bytes)
                reader.notes
            }
        }
        val footnotes = readNotes(DOCX_FOOTNOTES)
        val endnotes = readNotes(DOCX_ENDNOTES)
        val items = ArrayList<OfficeBlock>()
        for (wantEndnotes in listOf(false, true)) {
            for (ref in main.noteRefs) {
                if (ref.endnote != wantEndnotes) continue
                val note = (if (wantEndnotes) endnotes else footnotes)[ref.id] ?: continue
                items += OfficeBlock.Note(ref.label, note.text, note.spans)
            }
        }
        if (items.isNotEmpty() && blocks.size < MAX_BLOCKS) {
            blocks += OfficeBlock.Divider
            blocks.addAll(items)
        }
        return blocks
    }

    private fun parseOdt(
        body: ByteArray,
        parts: Map<String, ByteArray>,
        pending: MutableList<PendingImage>,
    ): MutableList<OfficeBlock> {
        val factory: ParserFactory = { newParser(it) }
        val sheet = OdfStyles()
        // Named styles first, so an automatic style in content.xml can override one of the same name.
        parts[ODT_STYLES]?.let { bytes -> optionalPart(Unit) { readOdfStyles(bytes, sheet, factory) } }
        optionalPart(Unit) { readOdfStyles(body, sheet, factory) }
        return OdtBodyParser(factory, sheet, pending).parse(body)
    }

    companion object {
        private const val DOCX_DIR = "word"
        private const val DOCX_BODY = "word/document.xml"
        private const val DOCX_RELS = "word/_rels/document.xml.rels"
        private const val DOCX_STYLES = "word/styles.xml"
        private const val DOCX_NUMBERING = "word/numbering.xml"
        private const val DOCX_FOOTNOTES = "word/footnotes.xml"
        private const val DOCX_ENDNOTES = "word/endnotes.xml"
        private const val ODT_BODY = "content.xml"
        private const val ODT_STYLES = "styles.xml"

        private val DOCX_PARTS = setOf(
            DOCX_BODY, DOCX_RELS, DOCX_STYLES, DOCX_NUMBERING, DOCX_FOOTNOTES, DOCX_ENDNOTES,
        )
        private val ODT_PARTS = setOf(ODT_BODY, ODT_STYLES)

        const val MAX_BLOCKS = 20_000
        const val MAX_TABLE_ROWS = 2_000
        const val MAX_IMAGES = 200
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_MEDIA_BYTES = 24 * 1024 * 1024

        /** Cap on the body XML part read into memory before parsing. */
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024
        private const val MEDIA_COPY_BUFFER = 64 * 1024

        /** Parts that only add formatting (styles, numbering, notes): bigger than this and they are ignored. */
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
