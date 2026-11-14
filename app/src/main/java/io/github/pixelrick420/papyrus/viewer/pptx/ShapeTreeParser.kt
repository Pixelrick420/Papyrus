package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** One slide's resolved nodes, and whether anything in it had to be dropped. */
internal class SlideScene(val part: String, val scene: Scene, val incomplete: Boolean)

/** A shape tree read off one part, before inheritance is resolved. */
internal class ParsedShapeTree(
    val nodes: List<SceneNode>,
    val background: XNode?,
    val showMasterShapes: Boolean,
    val incomplete: Boolean,
)

/** A deck's slides in presentation order; a dropped slide or shape marks the document incomplete. */
internal class DeckScenes(val slides: List<SlideScene>) {

    val incomplete: Boolean get() = slides.any { it.incomplete }
}

internal class ShapeTreeParser(
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE, limits)

    fun parse(pkg: PptxPackage, part: String, size: SceneSize): SlideScene {
        val tree = parsePart(pkg, part)
        return SlideScene(part, Scene(size, tree.nodes), tree.incomplete)
    }

    /** Any part that holds a `p:spTree`: a slide, a layout, a master, or a notes page. */
    fun parsePart(pkg: PptxPackage, part: String, root: String = "sld"): ParsedShapeTree {
        val bytes = pkg.part(part) ?: throw PptxFormatException("$part is missing")
        val reading = SlideReading(reader.open(bytes.inputStream(), part), part, limits)
        val nodes = reading.read(root)
        return ParsedShapeTree(nodes, reading.background, reading.showMasterShapes, reading.incomplete)
    }

    fun parseAll(pkg: PptxPackage, presentation: PresentationInfo): DeckScenes {
        val size = SceneSize(presentation.size.widthEmu.toDouble(), presentation.size.heightEmu.toDouble())
        return DeckScenes(parts(pkg, presentation).map { part -> slideOrEmpty(pkg, part, size) })
    }

    private fun slideOrEmpty(pkg: PptxPackage, part: String, size: SceneSize): SlideScene =
        runCatching { parse(pkg, part, size) }
            .getOrElse { SlideScene(part, Scene(size, emptyList()), incomplete = true) }

    fun parts(pkg: PptxPackage, presentation: PresentationInfo): List<String> {
        val related = presentation.slides.mapNotNull { ref -> pkg.targetOf(pkg.mainPart, ref.relationshipId) }
        val declared = pkg.contentTypes.ofType(SLIDE_TYPE).mapNotNull { pkg.findPart(it) }
        return (related + declared).distinctBy { it.lowercase() }
    }

    private companion object {
        const val SLIDE_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    }
}

/**
 * One slide read straight off the pull parser: shapes are materialised one at a time, so a hostile shape costs its own
 * subtree and not the shapes before it.
 */
private class SlideReading(
    private val cursor: OoxmlCursor,
    private val part: String,
    private val limits: PptxLimits,
) {

    var incomplete = false
        private set

    /** The slide's own `p:bg`, before the layout and master are consulted. */
    var background: XNode? = null
        private set

    /** A slide or layout shows the next level's shapes unless it says `showMasterSp="0"`. */
    var showMasterShapes = true
        private set

    private var nodes = 0

    /** Set when the cursor itself can no longer be trusted, so the slide stops at the shapes already read. */
    private var truncated = false

    fun read(root: String = "sld"): List<SceneNode> {
        requireRoot(root)
        showMasterShapes = !isOff(cursor.attribute("showMasterSp"))
        if (!seekShapeTree()) throw PptxFormatException("$part has no shape tree")
        return readNodes()
    }

    private fun requireRoot(root: String) {
        while (cursor.next() != XmlPullParser.END_DOCUMENT) {
            if (cursor.eventType == XmlPullParser.START_TAG) {
                if (!cursor.name.matches(OoxmlNamespace.P, root)) throw PptxFormatException("$part is not a $root")
                return
            }
        }
        throw PptxFormatException("$part is empty")
    }

    private fun seekShapeTree(): Boolean {
        val slideDepth = cursor.depth
        while (cursor.next() != XmlPullParser.END_DOCUMENT) {
            if (cursor.eventType == XmlPullParser.START_TAG) {
                if (cursor.name.matches(OoxmlNamespace.P, "spTree")) return true
                if (cursor.name.matches(OoxmlNamespace.P, "bg")) background = cursor.readTree()
            }
            if (cursor.eventType == XmlPullParser.END_TAG && cursor.depth < slideDepth) return false
        }
        return false
    }

    private fun readNodes(): List<SceneNode> {
        val found = mutableListOf<SceneNode>()
        val treeDepth = cursor.depth
        while (!truncated) {
            when (cursor.next()) {
                XmlPullParser.START_TAG -> node()?.let(found::add)
                XmlPullParser.END_TAG -> if (cursor.depth < treeDepth) return found
                XmlPullParser.END_DOCUMENT -> return found
            }
        }
        return found
    }

    private fun node(): SceneNode? {
        if (cursor.name !in NODE_TAGS) {
            cursor.skipSubtree()
            return null
        }
        if (nodes >= limits.maxShapes) {
            incomplete = true
            truncated = true
            return null
        }
        val shapeDepth = cursor.depth
        val node = runCatching { nodeOf(cursor.readTree(), TOP_LEVEL) }.getOrNull()
        if (node != null) return node
        incomplete = true
        if (!recover(shapeDepth)) truncated = true
        return null
    }

    /** A failed [OoxmlCursor.readTree] leaves the cursor mid-subtree; skip whatever the shape still holds. */
    private fun recover(shapeDepth: Int): Boolean = runCatching {
        while (cursor.depth >= shapeDepth && cursor.eventType != XmlPullParser.END_DOCUMENT) cursor.next()
    }.isSuccess

    private fun nodeOf(tree: XNode, depth: Int): SceneNode? {
        if (nodes >= limits.maxShapes) {
            incomplete = true
            return null
        }
        return when (tree.name) {
            OoxmlName(OoxmlNamespace.P, "sp") -> shapeNode(tree)
            OoxmlName(OoxmlNamespace.P, "pic") -> pictureNode(tree)
            OoxmlName(OoxmlNamespace.P, "grpSp") -> groupNode(tree, depth)
            OoxmlName(OoxmlNamespace.P, "cxnSp") -> connectorNode(tree)
            OoxmlName(OoxmlNamespace.P, "graphicFrame") -> graphicFrameNode(tree)
            else -> null
        }?.also { nodes++ }
    }

    private fun shapeNode(tree: XNode): SceneShape? {
        val properties = tree.child(OoxmlNamespace.P, "spPr")
        val transform = properties?.child(OoxmlNamespace.A, "xfrm")
        val props = propsOf(tree, "nvSpPr")
        val custom = CustomGeometry.read(properties, limits)
        if (custom == CustomGeometry.Invalid) return null
        return SceneShape(
            id = props.idOf(),
            name = props?.attribute("name"),
            frame = rectOf(transform),
            hidden = isOn(props?.attribute("hidden")),
            placeholder = placeholderOf(tree),
            preset = presetOf(properties, transform),
            customGeometry = (custom as? CustomGeometry.Shape)?.shape,
            textBody = tree.child(OoxmlNamespace.P, "txBody"),
            style = tree.child(OoxmlNamespace.P, "style"),
            hyperlink = hyperlinkOf(props),
            transform = transform,
            properties = properties,
        )
    }

    private fun pictureNode(tree: XNode): ScenePicture? {
        val properties = tree.child(OoxmlNamespace.P, "spPr")
        val transform = properties?.child(OoxmlNamespace.A, "xfrm")
        val props = propsOf(tree, "nvPicPr")
        val blipFill = tree.child(OoxmlNamespace.P, "blipFill")
        val custom = CustomGeometry.read(properties, limits)
        if (custom == CustomGeometry.Invalid) return null
        val source = PictureFills.read(blipFill)
        return ScenePicture(
            id = props.idOf(),
            name = props?.attribute("name"),
            frame = rectOf(transform),
            hidden = isOn(props?.attribute("hidden")),
            relationshipId = source?.relationshipId,
            style = tree.child(OoxmlNamespace.P, "style"),
            hyperlink = hyperlinkOf(props),
            placeholder = placeholderOf(tree),
            transform = transform,
            properties = properties,
            source = source,
            mask = presetOf(properties, transform),
            customGeometry = (custom as? CustomGeometry.Shape)?.shape,
        )
    }

    private fun groupNode(tree: XNode, depth: Int): SceneGroup {
        val transform = tree.child(OoxmlNamespace.P, "grpSpPr")?.child(OoxmlNamespace.A, "xfrm")
        val props = propsOf(tree, "nvGrpSpPr")
        return SceneGroup(
            id = props.idOf(),
            name = props?.attribute("name"),
            frame = rectOf(transform),
            hidden = isOn(props?.attribute("hidden")),
            childFrame = childRectOf(transform),
            children = groupChildren(tree, depth),
            hyperlink = hyperlinkOf(props),
            rotation = transform?.attribute("rot")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
            flipH = isOn(transform?.attribute("flipH")),
            flipV = isOn(transform?.attribute("flipV")),
        )
    }

    private fun groupChildren(tree: XNode, depth: Int): List<SceneNode> {
        val shapes = tree.children.filter { it.name in NODE_TAGS }
        if (shapes.isEmpty()) return emptyList()
        if (depth >= limits.maxGroupDepth) {
            incomplete = true
            return emptyList()
        }
        return shapes.mapNotNull { child ->
            runCatching { nodeOf(child, depth + 1) }.getOrNull().also { if (it == null) incomplete = true }
        }
    }

    private fun connectorNode(tree: XNode): SceneConnector? {
        val properties = tree.child(OoxmlNamespace.P, "spPr")
        val transform = properties?.child(OoxmlNamespace.A, "xfrm")
        val props = propsOf(tree, "nvCxnSpPr")
        val frame = rectOf(transform)
        val preset = presetOf(properties, transform)
        val endpoints = frame?.let { endpointsOf(it, preset) }
        val custom = CustomGeometry.read(properties, limits)
        if (custom == CustomGeometry.Invalid) return null
        return SceneConnector(
            id = props.idOf(),
            name = props?.attribute("name"),
            frame = frame,
            hidden = isOn(props?.attribute("hidden")),
            start = endpoints?.first,
            end = endpoints?.second,
            preset = preset,
            customGeometry = (custom as? CustomGeometry.Shape)?.shape,
            style = tree.child(OoxmlNamespace.P, "style"),
            hyperlink = hyperlinkOf(props),
            transform = transform,
            properties = properties,
        )
    }

    private fun graphicFrameNode(tree: XNode): SceneGraphicFrame {
        val props = propsOf(tree, "nvGraphicFramePr")
        val data = tree.child(OoxmlNamespace.A, "graphic")?.child(OoxmlNamespace.A, "graphicData")
        return SceneGraphicFrame(
            id = props.idOf(),
            name = props?.attribute("name"),
            frame = rectOf(tree.child(OoxmlNamespace.P, "xfrm") ?: tree.child(OoxmlNamespace.A, "xfrm")),
            hidden = isOn(props?.attribute("hidden")),
            kind = kindOf(data?.attribute("uri")),
            relationshipId = relationshipOf(data),
            hyperlink = hyperlinkOf(props),
        )
    }

    private companion object {
        const val TOP_LEVEL = 1
    }
}

private val NODE_TAGS = setOf(
    OoxmlName(OoxmlNamespace.P, "sp"),
    OoxmlName(OoxmlNamespace.P, "pic"),
    OoxmlName(OoxmlNamespace.P, "grpSp"),
    OoxmlName(OoxmlNamespace.P, "cxnSp"),
    OoxmlName(OoxmlNamespace.P, "graphicFrame"),
)

private val RELATIONSHIP_ATTRIBUTES = setOf("id", "embed", "dm")

private const val DEFAULT_PLACEHOLDER_IDX = 0

private fun propsOf(tree: XNode, wrapper: String): XNode? =
    tree.child(OoxmlNamespace.P, wrapper)?.child(OoxmlNamespace.P, "cNvPr")

private fun XNode?.idOf(): Int? = this?.attribute("id")?.toIntOrNull()

private fun XNode?.number(name: String): Double? =
    this?.attribute(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun isOn(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)

private fun isOff(value: String?): Boolean = value == "0" || value.equals("false", ignoreCase = true)

private fun hyperlinkOf(props: XNode?): SceneHyperlink? {
    val click = props?.child(OoxmlNamespace.A, "hlinkClick") ?: return null
    val relationship = click.attribute(OoxmlNamespace.R, "id")
    val action = click.attribute("action")
    return if (relationship == null && action == null) null else SceneHyperlink(relationship, action)
}

private fun placeholderOf(tree: XNode): PlaceholderIdentity? {
    val ph = tree.child(OoxmlNamespace.P, "nvSpPr")
        ?.child(OoxmlNamespace.P, "nvPr")
        ?.child(OoxmlNamespace.P, "ph")
        ?: tree.child(OoxmlNamespace.P, "nvPicPr")
            ?.child(OoxmlNamespace.P, "nvPr")
            ?.child(OoxmlNamespace.P, "ph")
        ?: return null
    val idx = ph.attribute("idx")
    return PlaceholderIdentity(ph.attribute("type"), idx?.toIntOrNull() ?: DEFAULT_PLACEHOLDER_IDX, idx != null)
}

private fun presetOf(properties: XNode?, transform: XNode?): ScenePreset? {
    val geometry = properties?.child(OoxmlNamespace.A, "prstGeom") ?: return null
    val adjusts = geometry.child(OoxmlNamespace.A, "avLst")
        ?.childrenOf(OoxmlNamespace.A, "gd")
        .orEmpty()
        .mapNotNull { guide ->
            val name = guide.attribute("name") ?: return@mapNotNull null
            val formula = guide.attribute("fmla") ?: return@mapNotNull null
            name to formula
        }
        .toMap()
    return ScenePreset(
        preset = geometry.attribute("prst"),
        adjusts = adjusts,
        flipH = isOn(transform?.attribute("flipH")),
        flipV = isOn(transform?.attribute("flipV")),
    )
}

private fun endpointsOf(frame: SceneRect, preset: ScenePreset?): Pair<ScenePoint, ScenePoint> {
    val flipH = preset?.flipH == true
    val flipV = preset?.flipV == true
    val start = ScenePoint(if (flipH) frame.right else frame.x, if (flipV) frame.bottom else frame.y)
    val end = ScenePoint(if (flipH) frame.x else frame.right, if (flipV) frame.y else frame.bottom)
    return start to end
}

private fun rectOf(transform: XNode?): SceneRect? {
    if (transform == null) return null
    val offset = transform.child(OoxmlNamespace.A, "off") ?: return null
    val extent = transform.child(OoxmlNamespace.A, "ext") ?: return null
    return SceneRect(
        x = offset.number("x") ?: return null,
        y = offset.number("y") ?: return null,
        width = extent.number("cx") ?: return null,
        height = extent.number("cy") ?: return null,
    )
}

private fun childRectOf(transform: XNode?): SceneRect? {
    if (transform == null) return null
    val offset = transform.child(OoxmlNamespace.A, "chOff") ?: return null
    val extent = transform.child(OoxmlNamespace.A, "chExt") ?: return null
    return SceneRect(
        x = offset.number("x") ?: return null,
        y = offset.number("y") ?: return null,
        width = extent.number("cx") ?: return null,
        height = extent.number("cy") ?: return null,
    )
}

private fun kindOf(uri: String?): GraphicKind = when (uri?.substringAfterLast('/')?.lowercase()) {
    "table" -> GraphicKind.TABLE
    "chart" -> GraphicKind.CHART
    "diagram" -> GraphicKind.SMART_ART
    "ole" -> GraphicKind.OLE_OBJECT
    else -> GraphicKind.OTHER
}

private fun relationshipOf(data: XNode?): String? {
    if (data == null) return null
    val pending = ArrayDeque<XNode>().apply { addLast(data) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        node.attributes
            .firstOrNull { it.namespace == OoxmlNamespace.R && it.localName in RELATIONSHIP_ATTRIBUTES }
            ?.let { return it.value }
        node.children.forEach { pending.addLast(it) }
    }
    return null
}
