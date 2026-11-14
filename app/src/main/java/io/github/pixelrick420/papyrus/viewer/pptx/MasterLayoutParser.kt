package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** The `type` and `idx` a slide placeholder matches a layout or master placeholder on. */
internal data class PlaceholderIdentity(
    val type: String?,
    val idx: Int,
    /** `idx` was written in the file; an absent one matches by type only. */
    val indexExplicit: Boolean = false,
)

/** A placeholder a master or layout lends to a slide, with the geometry and list style it lends with it. */
internal class Placeholder(
    val identity: PlaceholderIdentity,
    val shapeId: Int?,
    val name: String?,
    val frame: SceneRect?,
    val listStyle: XNode?,
)

/** Placeholder lookups by index and by type; the first declaration of a key wins, as PowerPoint does. */
internal class PlaceholderTable(
    private val byIdx: Map<Int, Placeholder>,
    private val byType: Map<String, Placeholder>,
) {

    fun byIdx(idx: Int): Placeholder? = byIdx[idx]

    fun byType(type: String): Placeholder? = byType[type]

    companion object {
        val EMPTY = PlaceholderTable(emptyMap(), emptyMap())
    }
}

/** `p:txStyles`, held whole so the inheritance rules (011.md) can resolve a slide's text against it. */
internal class MasterTextStyles(
    val titleStyle: XNode?,
    val bodyStyle: XNode?,
    val otherStyle: XNode?,
)

internal class SlideLayout(
    val part: String,
    val name: String?,
    /** The master this layout names by relationship, or null when the relationship is absent or dangling. */
    val masterPart: String?,
    val showMasterShapes: Boolean,
    /** `clrMapOvr`; null when the layout takes its master's map through `a:masterClrMapping`. */
    val colorMapOverride: ColorMap?,
    val placeholders: PlaceholderTable,
)

internal class SlideMaster(
    val part: String,
    val themePart: String?,
    val theme: Theme?,
    val colorMap: ColorMap,
    val textStyles: MasterTextStyles,
    val placeholders: PlaceholderTable,
    val layouts: List<SlideLayout>,
)

/** Masters and layouts parsed from one package, each part parsed once however many other parts name it. */
internal class MasterLayouts(val masters: List<SlideMaster>, val layouts: List<SlideLayout>) {

    fun master(part: String): SlideMaster? = masters.firstOrNull { it.part.equals(part, ignoreCase = true) }

    fun layout(part: String): SlideLayout? = layouts.firstOrNull { it.part.equals(part, ignoreCase = true) }

    fun layoutsOf(master: SlideMaster): List<SlideLayout> =
        layouts.filter { it.masterPart.equals(master.part, ignoreCase = true) }
}

internal class MasterLayoutParser(
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE, limits)

    fun parse(pkg: PptxPackage): MasterLayouts {
        val themes = HashMap<String, Theme>()
        val roots = masterParts(pkg).mapNotNull { part -> read(pkg, part, "sldMaster")?.let { part to it } }
        val layouts = layoutParts(pkg, roots).mapNotNull { part -> parseLayout(pkg, part) }
        val masters = roots.map { (part, root) -> parseMaster(pkg, part, root, layouts, themes) }
        return MasterLayouts(masters, layouts)
    }

    private fun masterParts(pkg: PptxPackage): List<String> {
        val declared = pkg.contentTypes.ofType(SLIDE_MASTER_TYPE)
        val related = pkg.relationshipsOf(pkg.mainPart)
            .filter { it.isType("slideMaster") }
            .map { it.target }
        return (declared + related).distinctBy { it.lowercase() }.mapNotNull { pkg.findPart(it) }
    }

    private fun layoutParts(pkg: PptxPackage, roots: List<Pair<String, XNode>>): List<String> {
        val listed = roots.flatMap { (part, root) ->
            root.child(OoxmlNamespace.P, "sldLayoutIdLst")
                ?.childrenOf(OoxmlNamespace.P, "sldLayoutId")
                .orEmpty()
                .mapNotNull { entry ->
                    entry.attribute(OoxmlNamespace.R, "id")?.let { pkg.targetOf(part, it) }
                }
        }
        val declared = pkg.contentTypes.ofType(SLIDE_LAYOUT_TYPE).mapNotNull { pkg.findPart(it) }
        return (listed + declared).distinctBy { it.lowercase() }
    }

    private fun parseLayout(pkg: PptxPackage, part: String): SlideLayout? {
        val root = read(pkg, part, "sldLayout") ?: return null
        val master = pkg.relationshipsOf(part).firstOrNull { it.isType("slideMaster") }
        return SlideLayout(
            part = part,
            name = root.child(OoxmlNamespace.P, "cSld")?.attribute("name"),
            masterPart = master?.let { pkg.targetOf(part, it.id) },
            showMasterShapes = !isFalse(root.attribute("showMasterSp")),
            colorMapOverride = root.child(OoxmlNamespace.P, "clrMapOvr")
                ?.child(OoxmlNamespace.A, "overrideClrMapping")
                ?.let(ColorMap::from),
            placeholders = placeholdersOf(root.child(OoxmlNamespace.P, "cSld")?.child(OoxmlNamespace.P, "spTree")),
        )
    }

    private fun parseMaster(
        pkg: PptxPackage,
        part: String,
        root: XNode,
        layouts: List<SlideLayout>,
        themes: MutableMap<String, Theme>,
    ): SlideMaster {
        val themePart = pkg.relationshipsOf(part)
            .firstOrNull { it.isType("theme") }
            ?.let { pkg.targetOf(part, it.id) }
        val textStyles = root.child(OoxmlNamespace.P, "txStyles")
        return SlideMaster(
            part = part,
            themePart = themePart,
            theme = themePart?.let { themeOf(pkg, it, themes) },
            colorMap = ColorMap.from(root.child(OoxmlNamespace.P, "clrMap")),
            textStyles = MasterTextStyles(
                titleStyle = textStyles?.child(OoxmlNamespace.P, "titleStyle"),
                bodyStyle = textStyles?.child(OoxmlNamespace.P, "bodyStyle"),
                otherStyle = textStyles?.child(OoxmlNamespace.P, "otherStyle"),
            ),
            placeholders = placeholdersOf(root.child(OoxmlNamespace.P, "cSld")?.child(OoxmlNamespace.P, "spTree")),
            layouts = layouts.filter { it.masterPart.equals(part, ignoreCase = true) },
        )
    }

    private fun themeOf(pkg: PptxPackage, part: String, themes: MutableMap<String, Theme>): Theme? =
        themes[part.lowercase()] ?: parseTheme(pkg, part)?.also { themes[part.lowercase()] = it }

    private fun parseTheme(pkg: PptxPackage, part: String): Theme? {
        val bytes = pkg.part(part) ?: return null
        return runCatching { ThemeParser(newParserFactory, limits).parse(bytes, part) }.getOrNull()
    }

    private fun read(pkg: PptxPackage, part: String, expected: String): XNode? {
        val bytes = pkg.part(part) ?: return null
        val root = reader.readTree(bytes.inputStream(), part) ?: return null
        return root.takeIf { it.name.matches(OoxmlNamespace.P, expected) }
    }

    private fun placeholdersOf(tree: XNode?): PlaceholderTable {
        if (tree == null) return PlaceholderTable.EMPTY
        val found = mutableListOf<Placeholder>()
        collectPlaceholders(tree, found)
        val byIdx = LinkedHashMap<Int, Placeholder>()
        val byType = LinkedHashMap<String, Placeholder>()
        for (placeholder in found.take(limits.maxShapes)) {
            byIdx.putIfAbsent(placeholder.identity.idx, placeholder)
            placeholder.identity.type?.let { byType.putIfAbsent(it, placeholder) }
        }
        return PlaceholderTable(byIdx, byType)
    }

    private fun collectPlaceholders(node: XNode, into: MutableList<Placeholder>) {
        for (child in node.children) {
            placeholderOf(child)?.let(into::add)
            if (child.name.matches(OoxmlNamespace.P, "grpSp")) collectPlaceholders(child, into)
        }
    }

    private fun placeholderOf(shape: XNode): Placeholder? {
        val properties = shape.children.firstOrNull { it.name in SHAPE_PROPERTIES } ?: return null
        val ph = properties.child(OoxmlNamespace.P, "nvPr")?.child(OoxmlNamespace.P, "ph") ?: return null
        val container = properties.child(OoxmlNamespace.P, "cNvPr")
        val transform = shape.child(OoxmlNamespace.P, "spPr")?.child(OoxmlNamespace.A, "xfrm")
            ?: shape.child(OoxmlNamespace.P, "xfrm")
        val idx = ph.attribute("idx")
        return Placeholder(
            identity = PlaceholderIdentity(
                type = ph.attribute("type"),
                idx = idx?.toIntOrNull() ?: DEFAULT_IDX,
                indexExplicit = idx != null,
            ),
            shapeId = container?.attribute("id")?.toIntOrNull(),
            name = container?.attribute("name"),
            frame = transform?.let(::rectOf),
            listStyle = shape.child(OoxmlNamespace.P, "txBody")?.child(OoxmlNamespace.A, "lstStyle"),
        )
    }

    private fun rectOf(transform: XNode): SceneRect? {
        val offset = transform.child(OoxmlNamespace.A, "off") ?: return null
        val extent = transform.child(OoxmlNamespace.A, "ext") ?: return null
        return SceneRect(
            x = offset.attribute("x")?.toDoubleOrNull() ?: return null,
            y = offset.attribute("y")?.toDoubleOrNull() ?: return null,
            width = extent.attribute("cx")?.toDoubleOrNull() ?: return null,
            height = extent.attribute("cy")?.toDoubleOrNull() ?: return null,
        )
    }

    private fun isFalse(value: String?): Boolean = value != null && value.lowercase() in FALSE_VALUES

    private companion object {
        const val DEFAULT_IDX = 0

        val FALSE_VALUES = setOf("0", "false")

        val SHAPE_PROPERTIES = setOf(
            OoxmlName(OoxmlNamespace.P, "nvSpPr"),
            OoxmlName(OoxmlNamespace.P, "nvPicPr"),
            OoxmlName(OoxmlNamespace.P, "nvGraphicFramePr"),
        )
    }
}

private fun PptxRelationship.isType(name: String): Boolean =
    type.substringAfterLast('/').equals(name, ignoreCase = true)

private const val SLIDE_MASTER_TYPE =
    "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"

private const val SLIDE_LAYOUT_TYPE =
    "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
