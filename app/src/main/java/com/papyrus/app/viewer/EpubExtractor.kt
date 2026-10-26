package com.papyrus.app.viewer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * An EPUB as the Office viewer shows it: [blocks] in reading order, an [outline] from the book's own table of
 * contents (null when it has none the reader can use, so the headings stand in), and whether anything was
 * left out ([incomplete]): a chapter that could not be read, or a limit that was reached. [anchors] maps every
 * place a link inside the book can name (a chapter, or an element of it by `id`) to the block it lands on.
 */
class EpubBook(
    val blocks: List<OfficeBlock>,
    val outline: List<OutlineEntry>?,
    val incomplete: Boolean,
    val anchors: Map<BookLink, Int> = emptyMap(),
)

/**
 * EPUB extraction with the platform XML parser and `java.util.zip`: the container names the package, the
 * package's spine gives the reading order, and each XHTML chapter becomes [OfficeBlock]s, so find, outline,
 * zoom, reading position and bookmarks all work as they do for a Word file. The parser comes through
 * [newParserFactory] for the same reason as in [OfficeTextExtractor]: the platform's is a stub in JVM tests.
 */
class EpubExtractor(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String = { OfficeTextExtractor.guessMimeType(it) },
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: EpubLimits = EpubLimits(),
    private val blockBudget: Int = OfficeTextExtractor.MAX_BLOCKS,
) {

    /**
     * [openStream] must return a fresh stream on every call: the archive is walked once for the text and once
     * more for the images. A [previewChapters] count reads only that many chapters and no images, for a
     * thumbnail's first lines, and without ever holding the whole book.
     *
     * @throws EpubProtectedException if the book is DRM-protected.
     * @throws EpubFormatException if the file is not a usable EPUB.
     */
    fun extract(openStream: () -> InputStream, previewChapters: Int? = null): EpubBook {
        val archive = EpubArchive(openStream, limits)
        val factory: ParserFactory = { newParser(it) }
        var incomplete = false

        val first = archive.scan(wanted = if (previewChapters == null) ::isTextPart else ::isStructurePart)
        if (first.skippedParts > 0) incomplete = true
        var names = EpubNames(first.parts)
        checkNotProtected(names, factory)
        val pkg = readPackage(names, factory)
        val chapters = pkg.readingOrder().filter { (it.isDocument || it.isRasterImage) && it.path != null }

        val toRead = if (previewChapters == null) chapters else chapters.take(previewChapters)
        if (previewChapters != null) {
            val wanted = toRead.mapNotNullTo(HashSet()) { it.path }
            val more = archive.scan(wanted = { it in wanted }, done = { parts -> wanted.all { it in parts } })
            names = EpubNames(first.parts + more.parts)
        }

        val blocks = ArrayList<OfficeBlock>()
        val pending = ArrayList<PendingImage>()
        val sink = EpubSink(blocks, pending, limits)
        // Where each chapter's first block is, before any image goes in; the table of contents jumps there.
        val starts = HashMap<String, Int>()
        // The archive's own spelling of each chapter's path, which the parser records its anchors under.
        val spelledAs = HashMap<String, String>()
        for (item in toRead) {
            if (sink.full) {
                incomplete = true
                break
            }
            val path = item.path ?: continue
            if (item.isRasterImage) {
                // A spine item that is a picture alone (a cover page): it is the chapter.
                starts.putIfAbsent(path, blocks.size)
                pending += PendingImage(path, blocks.size, null, null)
                continue
            }
            val key = names.find(path)
            val bytes = key?.let { names.bytes(it) }
            if (key == null || bytes == null) {
                incomplete = true
                continue
            }
            val blockMark = blocks.size
            val pendingMark = pending.size
            val anchorMark = sink.anchorMark()
            try {
                EpubHtmlParser(factory, sink).parse(bytes, key)
                starts[path] = blockMark
                spelledAs[key] = path
            } catch (_: XmlPullParserException) {
                incomplete = true
                rollBack(sink, blockMark, pendingMark, anchorMark)
            } catch (_: IOException) {
                incomplete = true
                rollBack(sink, blockMark, pendingMark, anchorMark)
            } catch (_: RuntimeException) {
                // A parser can fail in ways of its own on bytes that are not markup (a bad numeric reference);
                // whatever the cause, the chapter is lost and the book is not.
                incomplete = true
                rollBack(sink, blockMark, pendingMark, anchorMark)
            }
        }

        val media = if (previewChapters == null) {
            OfficeMedia(mediaDir, mimeByName).insertImages(openStream, pending, blocks, blockBudget)
        } else {
            OfficeMedia.Result(emptyList(), 0)
        }
        // Without a directory the caller asked for no images, so their absence is not a loss.
        if (mediaDir != null && media.dropped > 0) incomplete = true
        if (sink.truncated) incomplete = true

        if (previewChapters != null) return EpubBook(blocks, null, incomplete)
        val anchors = anchorsOf(sink, starts, spelledAs, media, blocks.size)
        return EpubBook(blocks, outlineOf(pkg, names, factory, starts, anchors, media, blocks.size), incomplete, anchors)
    }

    /**
     * Every place a link can name, as the block it lands on once the images are in: each `id` the chapters
     * noted, and each chapter's own start. An anchor at the very end of the book names no block and is dropped.
     */
    private fun anchorsOf(
        sink: EpubSink,
        starts: Map<String, Int>,
        spelledAs: Map<String, String>,
        media: OfficeMedia.Result,
        blockCount: Int,
    ): Map<BookLink, Int> {
        val out = HashMap<BookLink, Int>()
        for ((link, anchor) in sink.anchors) {
            // An element lands after the pictures that came before it; a picture lands on itself.
            val index = if (anchor.onImage) media.shifted(anchor.block) else media.shiftedPast(anchor.block)
            out.putIfAbsent(BookLink(spelledAs[link.path] ?: link.path, link.fragment), index)
        }
        for ((path, start) in starts) out[BookLink(path, null)] = media.shifted(start)
        out.values.removeAll { it >= blockCount }
        return out
    }

    /**
     * The cover image's bytes if the package names one, reading only the package and then that one entry; null
     * when there is none, it is not a raster image, or the file cannot be read. Nothing else is extracted.
     */
    fun readCover(openStream: () -> InputStream): ByteArray? = try {
        val archive = EpubArchive(openStream, limits.copy(maxPartBytes = OfficeTextExtractor.MAX_IMAGE_BYTES))
        val names = EpubNames(archive.scan(wanted = ::isStructurePart).parts)
        val coverPath = readPackage(names) { stream -> newParser(stream) }.coverItem()?.path
        coverPath?.let { path -> archive.scan(wanted = { it == path }, done = { path in it }).parts[path] }
    } catch (_: IOException) {
        null
    }

    /** Takes back everything a chapter that failed part way had already put in the sink. */
    private fun rollBack(sink: EpubSink, blockMark: Int, pendingMark: Int, anchorMark: Int) {
        sink.blocks.subList(blockMark, sink.blocks.size).clear()
        sink.pending.subList(pendingMark, sink.pending.size).clear()
        sink.rollBackAnchors(anchorMark)
    }

    private fun newParser(stream: InputStream): XmlPullParser =
        newParserFactory().apply {
            // Namespaces stay in the raw name ("epub:type"); the readers match on it. DOCTYPE processing stays
            // off, which is also what keeps a chapter's DOCTYPE from ever being fetched.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

    private fun checkNotProtected(names: EpubNames, factory: ParserFactory) {
        if (names.find(RIGHTS) != null) throw EpubProtectedException()
        val encryption = names.bytes(ENCRYPTION) ?: return
        if (EpubEncryption.isProtected(encryption, factory)) throw EpubProtectedException()
    }

    private fun readPackage(names: EpubNames, factory: ParserFactory): EpubPackage {
        val container = names.bytes(CONTAINER) ?: throw EpubFormatException("no META-INF/container.xml: not an EPUB")
        val opfPath = try {
            EpubPackageReader.opfPath(container, factory)
        } catch (e: XmlPullParserException) {
            throw EpubFormatException("container.xml is not valid", e)
        } ?: throw EpubFormatException("container.xml names no package")
        val actual = names.find(opfPath) ?: throw EpubFormatException("package document $opfPath is missing")
        val opf = names.bytes(actual) ?: throw EpubFormatException("package document $opfPath is missing")
        return try {
            EpubPackageReader.readPackage(actual, opf, factory)
        } catch (e: XmlPullParserException) {
            throw EpubFormatException("package document is not valid", e)
        }
    }

    /** The book's own table of contents as outline entries, or null if it has none that leads anywhere. */
    private fun outlineOf(
        pkg: EpubPackage,
        names: EpubNames,
        factory: ParserFactory,
        starts: Map<String, Int>,
        anchors: Map<BookLink, Int>,
        media: OfficeMedia.Result,
        blockCount: Int,
    ): List<OutlineEntry>? {
        if (blockCount == 0) return null
        val points = readPoints(pkg, names, factory)
        if (points.isEmpty()) return null
        val folded = starts.mapKeys { it.key.lowercase() }
        // XML ids are case-sensitive, a file name may not be: only the path is folded.
        val foldedAnchors = anchors.mapKeys { BookLink(it.key.path.lowercase(), it.key.fragment) }
        val entries = points.mapNotNull { point ->
            val path = point.path ?: return@mapNotNull OutlineEntry(NO_OUTLINE_TARGET, point.depth, point.title)
            // An entry that names an element lands on it; one whose element is gone lands on its chapter.
            val named = point.fragment?.let { anchors[BookLink(path, it)] ?: foldedAnchors[BookLink(path.lowercase(), it)] }
            val index = named
                ?: (starts[path] ?: folded[path.lowercase()])?.let(media::shifted)
                ?: return@mapNotNull null
            OutlineEntry(index.coerceAtMost(blockCount - 1), point.depth, point.title)
        }
        // A list of headings that go nowhere is no outline: the headings of the text are better.
        if (entries.none { it.index != NO_OUTLINE_TARGET }) return null
        return explicitOutline(entries)
    }

    /** EPUB 3's navigation document if it yields entries, else EPUB 2's NCX; a part that cannot be read counts as absent. */
    private fun readPoints(pkg: EpubPackage, names: EpubNames, factory: ParserFactory): List<NavPoint> {
        val fromNav = pkg.navItem()?.path?.let { path ->
            names.find(path)?.let { actual ->
                names.bytes(actual)?.let { bytes -> readQuietly { EpubPackageReader.readNav(actual, bytes, factory) } }
            }
        }.orEmpty()
        if (fromNav.isNotEmpty()) return fromNav
        return pkg.ncxItem()?.path?.let { path ->
            names.find(path)?.let { actual ->
                names.bytes(actual)?.let { bytes -> readQuietly { EpubPackageReader.readNcx(actual, bytes, factory) } }
            }
        }.orEmpty()
    }

    private fun readQuietly(read: () -> List<NavPoint>): List<NavPoint> = try {
        read()
    } catch (_: XmlPullParserException) {
        emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    companion object {
        private const val CONTAINER = "META-INF/container.xml"
        private const val ENCRYPTION = "META-INF/encryption.xml"
        private const val RIGHTS = "META-INF/rights.xml"

        private val TEXT_SUFFIXES = listOf(".xhtml", ".html", ".htm", ".xml", ".opf", ".ncx")

        /** The parts read into memory for a whole book: markup of every kind, never pictures or fonts. */
        internal fun isTextPart(name: String): Boolean {
            val lower = name.lowercase()
            return TEXT_SUFFIXES.any { lower.endsWith(it) }
        }

        /** The parts that say what a book contains, without its chapters. */
        internal fun isStructurePart(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".opf") || lower == CONTAINER.lowercase() ||
                lower == ENCRYPTION.lowercase() || lower == RIGHTS.lowercase()
        }
    }
}
