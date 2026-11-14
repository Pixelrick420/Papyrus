package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.svg.SvgLimits
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class EpubFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

class EpubProtectedException : IOException("EPUB content is protected")

// Every bound on reading an EPUB in one value so a test can use small ones; the defaults sit far above a real book and far below what would hurt.
data class EpubLimits(
    // The five bounds on the archive itself are the ones Word and OpenDocument files are read under.
    val maxEntries: Int = ZipLimits().maxEntries,
    val maxEntryBytes: Long = ZipLimits().maxEntryBytes,
    val maxTotalBytes: Long = ZipLimits().maxTotalBytes,
    val maxRatio: Int = ZipLimits().maxRatio,
    val ratioFloorBytes: Long = ZipLimits().ratioFloorBytes,
    val maxPartBytes: Int = 16 * 1024 * 1024,
    val maxTextBytes: Long = 64L * 1024 * 1024,
    val maxBlocks: Int = OfficeTextExtractor.MAX_BLOCKS,
    val maxTableRows: Int = OfficeTextExtractor.MAX_TABLE_ROWS,
    val maxDepth: Int = 200,
    val maxAnchors: Int = 100_000,
    val maxSvgElements: Int = SvgLimits().maxElements,
    val maxSvgOps: Int = SvgLimits().maxOps,
) {
    val svgLimits: SvgLimits get() = SvgLimits(maxSvgElements, maxSvgOps, maxDepth)

    val zipLimits: ZipLimits get() = ZipLimits(maxEntries, maxEntryBytes, maxTotalBytes, maxRatio, ratioFloorBytes)
}

internal class EpubScan(val parts: Map<String, ByteArray>, val skippedParts: Int)

// Closes off the hostile cases: a name that could leave its directory (zip-slip), too many entries, an entry or total inflating past its cap, an implausible ratio (a bomb).
// Bytes are counted as they come out of [BoundedZipInputStream], not the sizes an entry declares, since a bomb declares what it likes.
internal class EpubArchive(
    private val openStream: () -> InputStream,
    private val limits: EpubLimits,
) {

    fun scan(wanted: (String) -> Boolean, done: (Map<String, ByteArray>) -> Boolean = { false }): EpubScan {
        val parts = LinkedHashMap<String, ByteArray>()
        var skipped = 0
        var kept = 0L
        openStream().use { input ->
            BoundedZipInputStream(input, limits.zipLimits) { reason -> EpubFormatException(reason) }.use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!isSafeEntryName(name)) throw EpubFormatException("unsafe entry name")
                    if (entry.isDirectory) continue

                    val keep = wanted(name)
                    val out = if (keep) ByteArrayOutputStream() else null
                    var overPart = false
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        if (out != null && !overPart) {
                            if (out.size() + n > limits.maxPartBytes) overPart = true else out.write(buffer, 0, n)
                        }
                    }
                    if (out == null) continue
                    if (overPart || kept + out.size() > limits.maxTextBytes) {
                        skipped++
                        continue
                    }
                    kept += out.size()
                    parts[name] = out.toByteArray()
                    if (done(parts)) break
                }
            }
        }
        return EpubScan(parts, skipped)
    }
}

// Backslashes count as separators, so `a\..\b` is caught as well as `a/../b`.
internal fun isSafeEntryName(name: String): Boolean {
    if (name.isEmpty() || name.contains('\u0000')) return false
    if (name.startsWith('/') || name.startsWith('\\')) return false
    if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
    return name.split('/', '\\').none { it == ".." }
}

private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

// A reference that climbs above the root, or names a URL (`http:`, `data:`, `file:`), is not a path of the book.
internal fun resolveEpubPath(baseDir: String, href: String): String? {
    val raw = href.trim().substringBefore('#').substringBefore('?')
    if (raw.isEmpty() || URL_SCHEME.containsMatchIn(raw)) return null
    val decoded = percentDecode(raw)
    val joined = if (decoded.startsWith("/")) decoded.removePrefix("/") else baseDir + decoded
    val out = ArrayList<String>()
    for (segment in joined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeAt(out.lastIndex)
            else -> out += segment
        }
    }
    return out.takeIf { it.isNotEmpty() }?.joinToString("/")
}

internal fun dirOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

internal fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ByteArrayOutputStream()
    val out = StringBuilder()
    fun flush() {
        if (bytes.size() > 0) {
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val hi = if (c == '%' && i + 2 < text.length) Character.digit(text[i + 1], HEX) else -1
        val lo = if (hi >= 0) Character.digit(text[i + 2], HEX) else -1
        if (hi >= 0 && lo >= 0) {
            bytes.write(hi * HEX + lo)
            i += 3
        } else {
            flush()
            out.append(c)
            i++
        }
    }
    flush()
    return out.toString()
}

private const val HEX = 16

/** A zip lookup that forgives a difference of case, as readers do, but prefers the exact name. */
internal class EpubNames(private val parts: Map<String, ByteArray>) {
    val names: Set<String> get() = parts.keys

    private val folded: Map<String, String> by lazy { parts.keys.associateBy { it.lowercase() } }

    fun find(path: String): String? = if (path in parts) path else folded[path.lowercase()]

    fun bytes(path: String): ByteArray? = find(path)?.let { parts[it] }
}

// Fonts are obfuscated by almost every producer and that is only a font; any other scheme means the text cannot be read, so the book is refused.
// A file that cannot be parsed is refused too: failing closed beats rendering ciphertext.
internal object EpubEncryption {

    private val FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )

    fun isProtected(encryptionXml: ByteArray, factory: ParserFactory): Boolean = try {
        val parser = factory(encryptionXml.inputStream())
        var protectedContent = false
        var open = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                open++
                if (parser.name.substringAfter(':') == "EncryptionMethod") {
                    val algorithm = parser.getAttributeValue(null, "Algorithm")
                    if (algorithm == null || algorithm !in FONT_OBFUSCATION) protectedContent = true
                }
            } else if (event == XmlPullParser.END_TAG) {
                open--
            }
            event = parser.next()
        }
        // A file that stops inside an element is damaged, whether or not this parser says so.
        protectedContent || open != 0
    } catch (_: XmlPullParserException) {
        true
    } catch (_: IOException) {
        true
    }
}
