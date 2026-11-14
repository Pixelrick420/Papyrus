package io.github.pixelrick420.papyrus.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal class ManifestItem(val id: String, val path: String?, val mediaType: String, val properties: Set<String>) {
    val isDocument: Boolean
        get() = mediaType == "application/xhtml+xml" || mediaType == "text/html" ||
            (mediaType.isEmpty() && path?.substringAfterLast('.', "")?.lowercase() in DOCUMENT_EXTENSIONS)

    val isImage: Boolean get() = mediaType.startsWith("image/")

    val isRasterImage: Boolean get() = mediaType.startsWith("image/") && mediaType != "image/svg+xml"

    private companion object {
        val DOCUMENT_EXTENSIONS = setOf("xhtml", "html", "htm", "xml")
    }
}

internal class SpineItem(val idref: String, val linear: Boolean, val properties: Set<String> = emptySet())

internal class ReadingItem(val item: ManifestItem, val ref: SpineItem)

private const val PRE_PAGINATED = "pre-paginated"
private const val ITEM_PRE_PAGINATED = "rendition:layout-pre-paginated"
private const val ITEM_REFLOWABLE = "rendition:layout-reflowable"

internal class EpubPackage(
    val opfPath: String,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val tocId: String?,
    val coverMetaId: String?,
    val pageProgression: String? = null,
    val renditionLayout: String? = null,
) {
    private val bookIsFixed: Boolean get() = renditionLayout?.trim() == PRE_PAGINATED

    val hasFixedLayout: Boolean
        get() = bookIsFixed || spine.any { it.linear && ITEM_PRE_PAGINATED in it.properties }

    fun isFixedPage(ref: SpineItem): Boolean = when {
        ITEM_REFLOWABLE in ref.properties -> false
        ITEM_PRE_PAGINATED in ref.properties -> true
        else -> bookIsFixed
    }

    fun navItem(): ManifestItem? = manifest.values.firstOrNull { "nav" in it.properties && it.path != null }

    fun ncxItem(): ManifestItem? =
        tocId?.let { manifest[it] }?.takeIf { it.path != null }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" && it.path != null }

    fun coverItem(): ManifestItem? {
        val byProperty = manifest.values.firstOrNull { "cover-image" in it.properties }
        val byMeta = coverMetaId?.let { manifest[it] }
        return listOfNotNull(byProperty, byMeta).firstOrNull { it.isRasterImage && it.path != null }
    }

    /** The reading order: linear items as written, then the non-linear ones, which are kept rather than lost. */
    fun readingOrder(): List<ManifestItem> = readingItems().map { it.item }

    fun readingItems(): List<ReadingItem> {
        val items = spine.mapNotNull { ref -> manifest[ref.idref]?.let { ReadingItem(it, ref) } }
        return items.filter { it.ref.linear } + items.filterNot { it.ref.linear }
    }
}

internal class NavPoint(val title: String, val path: String?, val depth: Int, val fragment: String? = null)

internal object EpubPackageReader {

    private const val CONTAINER_MEDIA_TYPE = "application/oebps-package+xml"

    private fun local(name: String?): String = name.orEmpty().substringAfter(':')

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

    private fun normalisePath(path: String): String? = resolveEpubPath("", path)

    @Throws(XmlPullParserException::class)
    fun readPackage(opfPath: String, opf: ByteArray, factory: ParserFactory): EpubPackage {
        val base = dirOf(opfPath)
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<SpineItem>()
        var tocId: String? = null
        var coverMetaId: String? = null
        var pageProgression: String? = null
        var renditionLayout: String? = null
        var layoutMetaOpen = false
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
                        val properties = parser.getAttributeValue(null, "properties").orEmpty()
                            .split(' ', '\t', '\n').filter { p -> p.isNotEmpty() }.toSet()
                        spine += SpineItem(it, parser.getAttributeValue(null, "linear")?.trim()?.lowercase() != "no", properties)
                    }
                    "meta" -> if (parser.getAttributeValue(null, "name") == "cover") {
                        coverMetaId = parser.getAttributeValue(null, "content")
                    } else if (parser.getAttributeValue(null, "property")?.trim() == "rendition:layout") {
                        layoutMetaOpen = true
                    }
                }
            } else if (event == XmlPullParser.TEXT && layoutMetaOpen) {
                renditionLayout = parser.text.trim()
            } else if (event == XmlPullParser.END_TAG) {
                layoutMetaOpen = false
            }
            event = parser.next()
        }
        if (manifest.isEmpty() || spine.isEmpty()) throw XmlPullParserException("package has no manifest or spine")
        return EpubPackage(opfPath, manifest, spine, tocId, coverMetaId, pageProgression, renditionLayout)
    }

    // The first nav of any kind stands in only when no nav says it is the table of contents; a label with no link is kept as a heading that goes nowhere.
    @Throws(XmlPullParserException::class)
    fun readNav(navPath: String, nav: ByteArray, factory: ParserFactory): List<NavPoint> {
        val base = dirOf(navPath)
        val parser = factory(nav.inputStream())
        val found = ArrayList<NavPoint>()
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

    private fun typeAttribute(parser: XmlPullParser): String {
        for (i in 0 until parser.attributeCount) {
            if (local(parser.getAttributeName(i)) == "type") return parser.getAttributeValue(i)
        }
        return ""
    }

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
