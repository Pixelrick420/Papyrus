package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The file is not a usable EPUB: not a ZIP, no package, or an archive built to hurt the reader. */
class EpubFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The book is DRM-protected: its text is encrypted and would read as garbage. */
class EpubProtectedException : IOException("EPUB content is protected")

/**
 * Every bound on reading an EPUB, in one value so a test can use small ones. The defaults sit far above a
 * real book (a long novel is a few MB of XHTML in a few hundred entries) and far below what would hurt.
 */
data class EpubLimits(
    val maxEntries: Int = 10_000,
    /** One entry's inflated size, and so what a single bomb entry can cost in time. */
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    /** Inflated bytes per compressed byte, judged once an entry is past [ratioFloorBytes]. */
    val maxRatio: Int = 100,
    val ratioFloorBytes: Long = 1024L * 1024,
    /** One text part (XHTML, OPF, NCX) held in memory. */
    val maxPartBytes: Int = 16 * 1024 * 1024,
    /** All text parts held together. */
    val maxTextBytes: Long = 64L * 1024 * 1024,
    val maxBlocks: Int = OfficeTextExtractor.MAX_BLOCKS,
    val maxTableRows: Int = OfficeTextExtractor.MAX_TABLE_ROWS,
    /** Element nesting in one chapter; the parser keeps its own stacks, so this is about memory, not the call stack. */
    val maxDepth: Int = 200,
    /** Places in the text a link can name (elements with an `id`), across the whole book. */
    val maxAnchors: Int = 100_000,
)

/** What one pass over the archive kept, and how many wanted parts it had to leave out. */
internal class EpubScan(val parts: Map<String, ByteArray>, val skippedParts: Int)

/**
 * One walk over an EPUB's ZIP with the hostile cases closed off: a name that could leave its directory
 * (zip-slip) fails the book, as does an archive with too many entries, an entry or total that inflates
 * past its cap, or one that compresses implausibly well (a bomb). The check counts bytes as they come out,
 * not the sizes the entry declares, since a bomb declares what it likes.
 */
internal class EpubArchive(
    private val openStream: () -> InputStream,
    private val limits: EpubLimits,
) {

    /**
     * Reads the entries [wanted] accepts into memory, up to the limits, and drains the rest to count it. Ends
     * early once [done] says the parts so far are enough; it is asked after each kept part.
     */
    fun scan(wanted: (String) -> Boolean, done: (Map<String, ByteArray>) -> Boolean = { false }): EpubScan {
        val parts = LinkedHashMap<String, ByteArray>()
        var skipped = 0
        var entries = 0
        var total = 0L
        var kept = 0L
        val counting = CountingInputStream(openStream())
        counting.use {
            ZipInputStream(counting).use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.maxEntries) throw EpubFormatException("too many entries")
                    val name = entry.name
                    if (!isSafeEntryName(name)) throw EpubFormatException("unsafe entry name")
                    if (entry.isDirectory) continue

                    val keep = wanted(name)
                    val out = if (keep) ByteArrayOutputStream() else null
                    var overPart = false
                    var size = 0L
                    val compressedFrom = counting.count
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > limits.maxEntryBytes || total > limits.maxTotalBytes) throw EpubFormatException("too large")
                        if (size >= limits.ratioFloorBytes) {
                            val compressed = (counting.count - compressedFrom).coerceAtLeast(1L)
                            if (size / compressed > limits.maxRatio) throw EpubFormatException("implausible compression")
                        }
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

    /** Counts what the ZIP layer pulls from the file, which is the compressed size of what it has inflated. */
    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    }
}

/**
 * Whether an archive entry's name stays inside the archive: not absolute (`/etc/x`, `\x`, `C:\x`), no `..`
 * segment, no NUL. Backslashes count as separators, so `a\..\b` is caught as well as `a/../b`.
 */
internal fun isSafeEntryName(name: String): Boolean {
    if (name.isEmpty() || name.contains('\u0000')) return false
    if (name.startsWith('/') || name.startsWith('\\')) return false
    if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return false
    return name.split('/', '\\').none { it == ".." }
}

private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * The archive path a reference points at, or null when it points outside the book. [baseDir] is the
 * directory of the file the reference is written in, with its trailing slash (empty at the root). The
 * fragment and query are dropped, `%20` and friends decoded as UTF-8, `.` and `..` folded away; a reference
 * that climbs above the root, or names a URL (`http:`, `data:`, `file:`), is not a path of the book.
 */
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

/** The directory part of [path] with its trailing slash, or empty for a file at the root. */
internal fun dirOf(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

/** `%XX` runs decoded together as UTF-8; a `%` not followed by two hex digits stays as written. */
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
    private val folded: Map<String, String> by lazy { parts.keys.associateBy { it.lowercase() } }

    fun find(path: String): String? = if (path in parts) path else folded[path.lowercase()]

    fun bytes(path: String): ByteArray? = find(path)?.let { parts[it] }
}

/**
 * What `META-INF/encryption.xml` says. Fonts are obfuscated with IDPF's or Adobe's algorithm by almost every
 * producer, and that is only a font: the text is readable. Anything else (the Adobe, Apple or Kobo schemes
 * all name their own algorithm) means the content cannot be read, so the book is refused rather than shown
 * as garbage. A file that cannot be parsed is refused too: failing closed beats rendering ciphertext.
 */
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
