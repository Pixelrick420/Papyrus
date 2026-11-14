package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.BoundedZipInputStream
import io.github.pixelrick420.papyrus.viewer.MEDIA_COPY_BUFFER
import io.github.pixelrick420.papyrus.viewer.OfficeTextExtractor
import io.github.pixelrick420.papyrus.viewer.ParserFactory
import io.github.pixelrick420.papyrus.viewer.PdfPageSource
import io.github.pixelrick420.papyrus.viewer.ZipLimits
import io.github.pixelrick420.papyrus.viewer.dirOf
import io.github.pixelrick420.papyrus.viewer.isSafeEntryName
import io.github.pixelrick420.papyrus.viewer.resolveEpubPath
import io.github.pixelrick420.papyrus.viewer.safeLink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class PptxFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

// Every bound on reading a deck in one value, so a test can use small ones. The defaults sit far above a real
// deck and far below what would hurt; a limit no parser consumes yet is pinned here for the parser that will.
// Bitmap memory is not repeated: it is PdfPageSource's existing budget, which both page sources share.
data class PptxLimits(
    val maxEntries: Int = ZipLimits().maxEntries,
    val maxEntryBytes: Long = ZipLimits().maxEntryBytes,
    val maxTotalBytes: Long = ZipLimits().maxTotalBytes,
    val maxRatio: Int = ZipLimits().maxRatio,
    val ratioFloorBytes: Long = ZipLimits().ratioFloorBytes,
    val maxXmlPartBytes: Int = 32 * 1024 * 1024,
    val maxXmlBytes: Long = 128L * 1024 * 1024,
    val maxXmlElements: Int = 500_000,
    val maxXmlDepth: Int = 200,
    val maxSlides: Int = 3_000,
    val maxShapes: Int = 10_000,
    val maxGroupDepth: Int = 32,
    val maxTextChars: Int = 500_000,
    val maxParagraphs: Int = 20_000,
    val maxGradientStops: Int = 64,
    val maxDashEntries: Int = 16,
    val maxImages: Int = 400,
    val maxImageBytes: Int = 32 * 1024 * 1024,
    val maxMediaBytes: Long = 160L * 1024 * 1024,
    val maxImagePixels: Long = 64_000_000,
    val maxGuideEvaluations: Int = 1_024,
    val maxPaths: Int = 256,
    val maxPathCommands: Int = 8_192,
    val maxTableColumns: Int = 200,
    val maxTableRows: Int = 2_000,
    val maxTableCells: Int = 50_000,
    val maxChartSeries: Int = 100,
    val maxChartPoints: Int = 50_000,
    val maxRenderEdgePx: Int = PdfPageSource.MAX_PAGE_HEIGHT_PX,
    val maxSlideEdgeInches: Int = 56,
    val maxSlideRenderMillis: Long = 5_000,
) {
    val zipLimits: ZipLimits get() = ZipLimits(maxEntries, maxEntryBytes, maxTotalBytes, maxRatio, ratioFloorBytes)
}

internal class PptxRelationship(
    val id: String,
    val type: String,
    /** The part name this relationship names, or the URL of an external hyperlink. */
    val target: String,
    val external: Boolean = false,
) {
    val isImage: Boolean get() = type.substringAfterLast('/').equals("image", ignoreCase = true)
}

// Part names are compared without case, as OPC requires, but the name a part was stored under is what is handed back.
internal class PptxContentTypes(defaults: Map<String, String>, overrides: Map<String, String>) {

    private val defaults = defaults.mapKeys { it.key.lowercase() }

    private val overrides = LinkedHashMap(overrides)

    private val folded = overrides.entries.associate { it.key.lowercase() to it.value }

    fun of(part: String): String? =
        folded[part.removePrefix("/").lowercase()] ?: defaults[part.substringAfterLast('.', "").lowercase()]

    fun ofType(type: String): List<String> = overrides.filterValues { it.equals(type, ignoreCase = true) }.keys.toList()

    fun mainPart(): String? = overrides.entries.firstOrNull { it.value.lowercase() in PRESENTATION_MAIN_TYPES }?.key

    internal companion object {
        val PRESENTATION_MAIN_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
            "application/vnd.ms-powerpoint.presentation.macroenabled.main+xml",
            "application/vnd.ms-powerpoint.template.macroenabled.main+xml",
            "application/vnd.ms-powerpoint.slideshow.macroenabled.main+xml",
        )
    }
}

internal class PptxPackage(
    val mainPart: String,
    val contentTypes: PptxContentTypes,
    private val parts: Map<String, ByteArray>,
    private val relationships: Map<String, List<PptxRelationship>>,
    private val media: Map<String, File>,
    /** Media a slide names that could not be written, whether because the budget refused it or it is absent. */
    val mediaDropped: Int,
) {

    private val foldedParts by lazy { parts.keys.associateBy { it.lowercase() } }

    private val foldedRelationships by lazy { relationships.keys.associateBy { it.lowercase() } }

    private val foldedMedia by lazy { media.keys.associateBy { it.lowercase() } }

    val partNames: Set<String> get() = parts.keys

    val mediaNames: Set<String> get() = media.keys

    fun findPart(name: String): String? = if (name in parts) name else foldedParts[name.lowercase()]

    fun part(name: String): ByteArray? = findPart(name)?.let { parts[it] }

    fun hasPart(name: String): Boolean = findPart(name) != null

    fun mediaFile(name: String): File? = mediaName(name)?.let { media[it] }

    private fun mediaName(name: String): String? = foldedMedia[name.lowercase()]

    fun relationshipsOf(part: String): List<PptxRelationship> =
        relationships[part] ?: foldedRelationships[part.lowercase()]?.let { relationships[it] }.orEmpty()

    fun relationship(part: String, id: String): PptxRelationship? =
        relationshipsOf(part).firstOrNull { it.id == id }

    /** The part a relationship names, or null when it is external or the package does not hold it. */
    fun targetOf(part: String, id: String): String? {
        val rel = relationship(part, id)?.takeUnless { it.external } ?: return null
        return findPart(rel.target) ?: mediaName(rel.target)
    }

    companion object {
        fun read(
            openStream: () -> InputStream,
            mediaDir: File? = null,
            limits: PptxLimits = PptxLimits(),
            newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        ): PptxPackage = PptxReader(mediaDir, limits, newParserFactory).read(openStream)
    }
}

private class PptxReader(
    private val mediaDir: File?,
    private val limits: PptxLimits,
    private val newParserFactory: () -> XmlPullParser,
) {

    private class Scan(val parts: Map<String, ByteArray>, val contentTypes: ByteArray?)

    fun read(openStream: () -> InputStream): PptxPackage {
        val scan = scanParts(openStream)
        val types = parseContentTypes(
            scan.contentTypes ?: throw PptxFormatException("no [Content_Types].xml: not a presentation"),
        )
        val rels = parseRelationships(scan.parts)
        val main = actualPart(mainPart(types, rels[""].orEmpty()), scan.parts)
            ?: throw PptxFormatException("main part ${types.mainPart()} is missing")
        if (types.ofType(SLIDE_CONTENT_TYPE).size > limits.maxSlides) throw PptxFormatException("too many slides")
        val (media, dropped) = readMedia(openStream, wantedMedia(rels))
        return PptxPackage(main, types, scan.parts, rels, media, dropped)
    }

    // One walk: XML and relationships in, everything else skipped, since which media a deck needs is only known
    // once its relationships are read.
    private fun scanParts(openStream: () -> InputStream): Scan {
        val parts = LinkedHashMap<String, ByteArray>()
        var contentTypes: ByteArray? = null
        var total = 0L
        val buffer = ByteArray(MEDIA_COPY_BUFFER)
        openStream().use { input ->
            BoundedZipInputStream(input, limits.zipLimits, ::formatError).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!isSafeEntryName(name) || isSkipped(name)) continue
                    if (name.equals(CONTENT_TYPES_PART, ignoreCase = true)) {
                        val bytes = readBounded(zip, limits.maxXmlPartBytes, buffer)
                            ?: throw PptxFormatException("$name is too large")
                        total += bytes.size
                        contentTypes = bytes
                    } else {
                        val lower = name.lowercase()
                        if (!lower.endsWith(".xml") && !lower.endsWith(".rels")) continue
                        val bytes = readBounded(zip, limits.maxXmlPartBytes, buffer)
                            ?: throw PptxFormatException("$name is too large")
                        total += bytes.size
                        parts.putIfAbsent(name, bytes)
                    }
                    if (total > limits.maxXmlBytes) throw PptxFormatException("xml parts are too large")
                }
            }
        }
        return Scan(parts, contentTypes)
    }

    private fun parseRelationships(parts: Map<String, ByteArray>): Map<String, List<PptxRelationship>> {
        val found = LinkedHashMap<String, List<PptxRelationship>>()
        for ((name, bytes) in parts) {
            if (!name.endsWith(".rels", ignoreCase = true)) continue
            val source = sourceOf(name) ?: continue
            found[source] = parseRels(bytes, dirOf(source))
        }
        return found
    }

    private fun parseRels(xml: ByteArray, baseDir: String): List<PptxRelationship> {
        val found = LinkedHashMap<String, PptxRelationship>()
        try {
            val parser = newParser(xml.inputStream())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "Relationship") {
                    relationship(parser, baseDir)?.let { found.putIfAbsent(it.id, it) }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            return emptyList()
        } catch (_: IOException) {
            return emptyList()
        }
        return found.values.toList()
    }

    private fun relationship(parser: XmlPullParser, baseDir: String): PptxRelationship? {
        val id = parser.getAttributeValue(null, "Id")?.trim()
        val type = parser.getAttributeValue(null, "Type")?.trim()
        val target = parser.getAttributeValue(null, "Target")?.trim()
        if (id.isNullOrEmpty() || type.isNullOrEmpty() || target.isNullOrEmpty()) return null
        val external = parser.getAttributeValue(null, "TargetMode").equals("External", ignoreCase = true)
        return if (external) {
            // An external target is kept for a hyperlink only: a picture, OLE object, sound or film is never followed.
            val link = target.takeIf { type.substringAfterLast('/').equals("hyperlink", ignoreCase = true) }
                ?.let(::safeLink)
            link?.let { PptxRelationship(id, type, it, external = true) }
        } else {
            resolveEpubPath(baseDir, target)?.let { PptxRelationship(id, type, it) }
        }
    }

    private fun mainPart(types: PptxContentTypes, packageRels: List<PptxRelationship>): String {
        types.mainPart()?.let { return it }
        val office = packageRels.firstOrNull {
            it.type.substringAfterLast('/').equals("officeDocument", ignoreCase = true)
        }?.target
        if (office != null && types.of(office)?.lowercase() in PptxContentTypes.PRESENTATION_MAIN_TYPES) return office
        throw PptxFormatException("not a presentation")
    }

    private fun actualPart(name: String, parts: Map<String, ByteArray>): String? =
        if (name in parts) name else parts.keys.firstOrNull { it.equals(name, ignoreCase = true) }

    private fun wantedMedia(rels: Map<String, List<PptxRelationship>>): Map<String, String> {
        val wanted = LinkedHashMap<String, String>()
        for (list in rels.values) {
            for (rel in list) {
                if (rel.external || !rel.isImage || !isMediaTarget(rel.target)) continue
                wanted.putIfAbsent(rel.target.lowercase(), rel.target)
            }
        }
        return wanted
    }

    private fun readMedia(openStream: () -> InputStream, wanted: Map<String, String>): Pair<Map<String, File>, Int> {
        if (wanted.isEmpty()) return emptyMap<String, File>() to 0
        val dir = mediaDir ?: return emptyMap<String, File>() to wanted.size
        val written = LinkedHashMap<String, File>()
        var total = 0L
        val buffer = ByteArray(MEDIA_COPY_BUFFER)
        openStream().use { input ->
            BoundedZipInputStream(input, limits.zipLimits, ::formatError).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = wanted[entry.name.lowercase()] ?: continue
                    // Reserve before reading: an entry the budget can never accept is not buffered at all.
                    if (written.size >= limits.maxImages) continue
                    if (entry.size > limits.maxImageBytes) continue
                    val bytes = readBounded(zip, limits.maxImageBytes, buffer) ?: continue
                    if (total + bytes.size > limits.maxMediaBytes) continue
                    val file = writeMedia(dir, target, bytes) ?: continue
                    total += bytes.size
                    written[target] = file
                }
            }
        }
        return written to (wanted.size - written.size)
    }

    private fun writeMedia(dir: File, zipName: String, bytes: ByteArray): File? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val root = dir.canonicalPath + File.separator
        val target = File(dir, OfficeTextExtractor.sanitiseName(zipName))
        if (!target.exists()) return target.writeOrNull(bytes, root)
        val base = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "bin" }
        var suffix = 1
        while (true) {
            val candidate = File(dir, "$base-$suffix.$ext")
            if (!candidate.exists()) return candidate.writeOrNull(bytes, root)
            suffix++
        }
    }

    private fun File.writeOrNull(bytes: ByteArray, root: String): File? {
        if (!canonicalPath.startsWith(root)) return null
        return try {
            writeBytes(bytes)
            this
        } catch (_: IOException) {
            null
        }
    }

    private fun readBounded(zip: ZipInputStream, cap: Int, buffer: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(minOf(cap, MEDIA_COPY_BUFFER))
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > cap) return null
            out.write(buffer, 0, n)
        }
    }

    private fun parseContentTypes(bytes: ByteArray): PptxContentTypes {
        val defaults = LinkedHashMap<String, String>()
        val overrides = LinkedHashMap<String, String>()
        var sawRoot = false
        try {
            val parser = newParser(bytes.inputStream())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "Types" -> sawRoot = true
                        "Default" -> {
                            val ext = parser.getAttributeValue(null, "Extension")?.trim()?.lowercase()
                            val type = parser.getAttributeValue(null, "ContentType")?.trim()
                            if (!ext.isNullOrEmpty() && !type.isNullOrEmpty()) defaults[ext] = type
                        }
                        "Override" -> {
                            val part = parser.getAttributeValue(null, "PartName")?.trim()?.removePrefix("/")
                            val type = parser.getAttributeValue(null, "ContentType")?.trim()
                            if (!part.isNullOrEmpty() && !type.isNullOrEmpty()) overrides[part] = type
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: XmlPullParserException) {
            throw PptxFormatException("content types are not valid", e)
        } catch (e: IOException) {
            throw PptxFormatException("content types are not valid", e)
        }
        if (!sawRoot) throw PptxFormatException("content types are not valid")
        return PptxContentTypes(defaults, overrides)
    }

    private fun newParser(stream: InputStream): XmlPullParser = newParserFactory().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(stream, null)
    }

    private fun formatError(reason: String): IOException = PptxFormatException(reason)

    // The `_rels/foo.xml.rels` part belongs to the part `foo.xml` beside its `_rels` folder; the package's own is `_rels/.rels`.
    private fun sourceOf(relsName: String): String? {
        val dir = dirOf(relsName)
        if (!dir.endsWith("_rels/", ignoreCase = true)) return null
        val file = relsName.substringAfterLast('/')
        if (!file.endsWith(".rels", ignoreCase = true)) return null
        return dir.dropLast("_rels/".length) + file.dropLast(".rels".length)
    }

    private fun isSkipped(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("ppt/embeddings/") ||
            lower.startsWith("ppt/printersettings/") ||
            lower.contains("thumbnail") ||
            lower.endsWith("vbaproject.bin")
    }

    private fun isMediaTarget(target: String): Boolean {
        val lower = target.lowercase()
        if (lower.startsWith("ppt/embeddings/") || lower.startsWith("ppt/printersettings/")) return false
        if (lower.contains("thumbnail")) return false
        return lower.substringAfterLast('.', "") in IMAGE_EXTENSIONS
    }

    private companion object {
        const val SLIDE_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"

        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tif", "tiff", "emf", "wmf")
    }
}

private const val CONTENT_TYPES_PART = "[Content_Types].xml"
