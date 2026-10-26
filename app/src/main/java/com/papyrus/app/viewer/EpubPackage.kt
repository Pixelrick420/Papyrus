package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/** One `item` of the OPF manifest, its [path] already resolved to an archive path (null if it points outside the book). */
internal class ManifestItem(val id: String, val path: String?, val mediaType: String, val properties: Set<String>) {
    val isDocument: Boolean
        get() = mediaType == "application/xhtml+xml" || mediaType == "text/html" ||
            (mediaType.isEmpty() && path?.substringAfterLast('.', "")?.lowercase() in DOCUMENT_EXTENSIONS)

    /** A raster picture BitmapFactory can read; SVG is not one. */
    val isRasterImage: Boolean get() = mediaType.startsWith("image/") && mediaType != "image/svg+xml"

    private companion object {
        val DOCUMENT_EXTENSIONS = setOf("xhtml", "html", "htm", "xml")
    }
}

/** An `itemref` of the spine; [linear] false marks auxiliary content (a footnote page, an alternate cover). */
internal class SpineItem(val idref: String, val linear: Boolean)

/** The reading order of an EPUB and what points at its table of contents and cover. */
internal class EpubPackage(
    val opfPath: String,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    /** `spine@toc`: the manifest id of the EPUB 2 NCX. */
    val tocId: String?,
    /** `<meta name="cover" content="id">`: the EPUB 2 way to name the cover image. */
    val coverMetaId: String?,
    /** `spine@page-progression-direction`: `rtl` for a book that is turned from the right. */
    val pageProgression: String? = null,
) {
    /** The EPUB 3 navigation document, or null. */
    fun navItem(): ManifestItem? = manifest.values.firstOrNull { "nav" in it.properties && it.path != null }

    /** The EPUB 2 NCX: by `spine@toc`, else by its media type. */
    fun ncxItem(): ManifestItem? =
        tocId?.let { manifest[it] }?.takeIf { it.path != null }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" && it.path != null }

    /** The cover image: EPUB 3's `cover-image` property, else EPUB 2's `meta name="cover"`; null if neither names a raster image. */
    fun coverItem(): ManifestItem? {
        val byProperty = manifest.values.firstOrNull { "cover-image" in it.properties }
        val byMeta = coverMetaId?.let { manifest[it] }
        return listOfNotNull(byProperty, byMeta).firstOrNull { it.isRasterImage && it.path != null }
    }

    /** The reading order: linear items as written, then the non-linear ones, which are kept rather than lost. */
    fun readingOrder(): List<ManifestItem> {
        val items = spine.mapNotNull { ref -> manifest[ref.idref]?.let { ref.linear to it } }
        return items.filter { it.first }.map { it.second } + items.filterNot { it.first }.map { it.second }
    }
}

/**
 * An entry of the table of contents: a [title], where it points ([path] in the archive, null for a heading that
 * links nowhere, and the [fragment] naming an element in it, when the entry did) and its nesting.
 */
internal class NavPoint(val title: String, val path: String?, val depth: Int, val fragment: String? = null)

/** Reads the parts of an EPUB that say what is in it and in what order. */
internal object EpubPackageReader {

    private const val CONTAINER_MEDIA_TYPE = "application/oebps-package+xml"

    private fun local(name: String?): String = name.orEmpty().substringAfter(':')

    /** `full-path` of the package document named in `META-INF/container.xml`, or null if it names none. */
    fun opfPath(container: ByteArray, factory: ParserFactory): String? {
        val parser = factory(container.inputStream())
        var first: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && local(parser.name) == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")?.trim().orEmpty()
                if (path.isNotEmpty()) {
                    if (parser.getAttributeValue(null, "media-type") == CONTAINER_MEDIA_TYPE) return normalisePath(path)
                    if (first == null) first = path
                }
            }
            event = parser.next()
        }
        return first?.let(::normalisePath)
    }

    /** A path as the container writes it, folded the way every other path in the book is; null if it escapes the root. */
    private fun normalisePath(path: String): String? = resolveEpubPath("", path)

    @Throws(XmlPullParserException::class)
    fun readPackage(opfPath: String, opf: ByteArray, factory: ParserFactory): EpubPackage {
        val base = dirOf(opfPath)
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<SpineItem>()
        var tocId: String? = null
        var coverMetaId: String? = null
        var pageProgression: String? = null
        val parser = factory(opf.inputStream())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (local(parser.name)) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            val properties = parser.getAttributeValue(null, "properties").orEmpty()
                                .split(' ', '\t', '\n').filter { it.isNotEmpty() }.toSet()
                            manifest[id] = ManifestItem(
                                id = id,
                                path = resolveEpubPath(base, href),
                                mediaType = parser.getAttributeValue(null, "media-type").orEmpty().lowercase(),
                                properties = properties,
                            )
                        }
                    }
                    "spine" -> {
                        tocId = parser.getAttributeValue(null, "toc")
                        pageProgression = parser.getAttributeValue(null, "page-progression-direction")
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let {
                        spine += SpineItem(it, parser.getAttributeValue(null, "linear")?.trim()?.lowercase() != "no")
                    }
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        coverMetaId = parser.getAttributeValue(null, "content")
                    }
                }
            }
            event = parser.next()
        }
        if (manifest.isEmpty() || spine.isEmpty()) throw XmlPullParserException("package has no manifest or spine")
        return EpubPackage(opfPath, manifest, spine, tocId, coverMetaId, pageProgression)
    }

    /**
     * The table of contents of an EPUB 3 navigation document: the `nav` whose `epub:type` is `toc` (the first
     * `nav` if none says so), its nested lists flattened with their depth. A label with no link is kept as a
     * heading that goes nowhere. [navPath] is where the document lives, since its links are relative to it.
     */
    @Throws(XmlPullParserException::class)
    fun readNav(navPath: String, nav: ByteArray, factory: ParserFactory): List<NavPoint> {
        val base = dirOf(navPath)
        val parser = factory(nav.inputStream())
        val found = ArrayList<NavPoint>()
        // The first nav of any kind stands in only when no nav says it is the table of contents.
        var fallback: List<NavPoint>? = null
        val tentative = ArrayList<NavPoint>()
        var insideNav = false
        var inToc = false
        var tocSeen = false
        var listDepth = 0
        var captureTag: String? = null
        var captureNesting = 0
        var captureHref: String? = null
        val title = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = local(parser.name)
                    when {
                        name == "nav" && !insideNav -> {
                            insideNav = true
                            inToc = typeAttribute(parser).split(' ').contains("toc")
                            if (inToc) tocSeen = true
                            listDepth = 0
                        }
                        !insideNav -> Unit
                        name == "ol" -> listDepth++
                        captureTag != null -> captureNesting++
                        (name == "a" || name == "span") && listDepth > 0 -> {
                            captureTag = name
                            captureNesting = 0
                            captureHref = if (name == "a") parser.getAttributeValue(null, "href") else null
                            title.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (captureTag != null) title.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = local(parser.name)
                    when {
                        !insideNav -> Unit
                        captureTag != null && captureNesting > 0 -> captureNesting--
                        captureTag != null && name == captureTag -> {
                            val label = cleanOutlineTitle(title.toString())
                            if (label.isNotEmpty()) {
                                val point = NavPoint(
                                    label,
                                    captureHref?.let { resolveEpubPath(base, it) },
                                    listDepth - 1,
                                    captureHref?.let { splitHref(it).fragment },
                                )
                                if (inToc) found += point else tentative += point
                            }
                            captureTag = null
                        }
                        name == "ol" -> listDepth--
                        name == "nav" -> {
                            if (!inToc && fallback == null) fallback = tentative.toList()
                            tentative.clear()
                            insideNav = false
                            inToc = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return if (tocSeen) found else fallback.orEmpty()
    }

    /** The `type` attribute whatever prefix the book gave the epub namespace (`epub:type`, `ops:type`). */
    private fun typeAttribute(parser: XmlPullParser): String {
        for (i in 0 until parser.attributeCount) {
            if (local(parser.getAttributeName(i)) == "type") return parser.getAttributeValue(i)
        }
        return ""
    }

    /** The table of contents of an EPUB 2 NCX: `navPoint`s in document order, nesting as their depth. */
    @Throws(XmlPullParserException::class)
    fun readNcx(ncxPath: String, ncx: ByteArray, factory: ParserFactory): List<NavPoint> {
        val base = dirOf(ncxPath)
        val parser = factory(ncx.inputStream())
        class Open(val depth: Int) {
            var title = ""
            var src: String? = null
        }
        val order = ArrayList<Open>()
        val stack = ArrayList<Open>()
        var inLabel = false
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "navPoint" -> Open(stack.size).also { stack += it; order += it }
                    "navLabel" -> if (stack.isNotEmpty()) inLabel = true
                    "text" -> if (inLabel) inText = true
                    "content" -> stack.lastOrNull()?.let { if (it.src == null) it.src = parser.getAttributeValue(null, "src") }
                }
                XmlPullParser.TEXT -> if (inText) stack.lastOrNull()?.let { it.title += parser.text }
                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "navPoint" -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    "navLabel" -> inLabel = false
                    "text" -> inText = false
                }
            }
            event = parser.next()
        }
        return order.mapNotNull { open ->
            val label = cleanOutlineTitle(open.title)
            if (label.isEmpty()) {
                null
            } else {
                NavPoint(label, open.src?.let { resolveEpubPath(base, it) }, open.depth, open.src?.let { splitHref(it).fragment })
            }
        }
    }
}
