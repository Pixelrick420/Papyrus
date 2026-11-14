package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * The text-only slide path for search and read-aloud: it reads a deck's text in shape-tree order and never builds
 * geometry or loads media, which is why [PptxPackage.read] is called with `mediaDir = null`.
 */
internal class SlideTextExtractor(
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE, limits)

    /** One entry per slide, in presentation order; a slide with no text is an empty string. */
    fun extract(openStream: () -> InputStream): List<String> {
        val pkg = PptxPackage.read(openStream, mediaDir = null, limits = limits, newParserFactory = newParserFactory)
        val mainPart = pkg.mainPart
        val bytes = pkg.part(mainPart) ?: throw PptxFormatException("$mainPart is missing")
        val presentation = PresentationParser(newParserFactory, limits).parse(bytes, mainPart)
        val parts = ShapeTreeParser(newParserFactory, limits).parts(pkg, presentation)
        val cap = TextCap(limits.maxTextChars)
        return parts.map { part -> runCatching { slideText(pkg, part, cap) }.getOrDefault("") }
    }

    private fun slideText(pkg: PptxPackage, part: String, cap: TextCap): String {
        val bytes = pkg.part(part) ?: return ""
        val root = reader.readTree(bytes.inputStream(), part) ?: return ""
        val tree = root.child(OoxmlNamespace.P, "cSld")?.child(OoxmlNamespace.P, "spTree") ?: return ""
        val lines = mutableListOf<String>()
        collect(tree, pkg, part, lines, 0)
        return lines.mapNotNull(cap::take).joinToString("\n")
    }

    private fun collect(parent: XNode, pkg: PptxPackage, part: String, lines: MutableList<String>, depth: Int) {
        for (node in parent.children) {
            when {
                node.name.matches(OoxmlNamespace.P, "grpSp") -> {
                    if (!isHidden(node) && depth < limits.maxGroupDepth) {
                        collect(node, pkg, part, lines, depth + 1)
                    }
                }
                node.name.matches(OoxmlNamespace.P, "sp") -> {
                    if (!isHidden(node)) addText(lines, node.child(OoxmlNamespace.P, "txBody"))
                }
                node.name.matches(OoxmlNamespace.P, "graphicFrame") -> {
                    if (!isHidden(node)) graphicFrame(node, pkg, part, lines)
                }
            }
        }
    }

    private fun graphicFrame(frame: XNode, pkg: PptxPackage, part: String, lines: MutableList<String>) {
        val data = frame.child(OoxmlNamespace.A, "graphic")?.child(OoxmlNamespace.A, "graphicData") ?: return
        when (data.attribute("uri")?.substringAfterLast('/')?.lowercase()) {
            "table" -> data.child(OoxmlNamespace.A, "tbl")?.let { tableText(it).takeIf(String::isNotEmpty) }?.let(lines::add)
            "chart" -> chartText(pkg, part, data)?.takeIf { it.isNotBlank() }?.let(lines::add)
            "diagram" -> smartArtText(pkg, part, data)?.takeIf { it.isNotBlank() }?.let(lines::add)
        }
    }

    private fun tableText(table: XNode): String = buildString {
        for (row in table.childrenOf(OoxmlNamespace.A, "tr")) {
            val cells = row.childrenOf(OoxmlNamespace.A, "tc")
                .filterNot(::isMerged)
                .mapNotNull { cell -> cell.child(OoxmlNamespace.A, "txBody")?.let(::textOfBody) }
                .map { it.replace('\n', ' ').trim() }
                .filter { it.isNotEmpty() }
            if (cells.isEmpty()) continue
            if (isNotEmpty()) append('\n')
            append(cells.joinToString(" "))
        }
    }

    private fun chartText(pkg: PptxPackage, part: String, data: XNode): String? {
        val relation = data.child(CHART, "chart")?.attribute(OoxmlNamespace.R, "id") ?: return null
        val chartPart = pkg.targetOf(part, relation) ?: return null
        val chart = readPart(pkg, chartPart)?.child(CHART, "chart") ?: return null
        val title = chart.child(CHART, "title")?.let(::chartTitle).orEmpty()
        val categories = chart.child(CHART, "plotArea")
            ?.let { firstDescendant(it, CHART, "ser") }
            ?.child(CHART, "cat")
            ?.let(::cachedValues)
            .orEmpty()
        return listOfNotNull(
            title.takeIf { it.isNotBlank() },
            categories.takeIf { it.isNotEmpty() }?.joinToString(" "),
        ).joinToString("\n")
    }

    private fun chartTitle(title: XNode): String {
        val text = title.child(CHART, "tx") ?: return ""
        text.child(CHART, "rich")?.let { return textOfBody(it) }
        val reference = text.child(CHART, "strRef") ?: text.child(CHART, "strLit") ?: return ""
        return cachedValues(reference).joinToString(" ")
    }

    private fun smartArtText(pkg: PptxPackage, part: String, data: XNode): String? {
        val relations = data.child(DIAGRAM, "relIds") ?: return null
        val relation = relations.attribute(OoxmlNamespace.R, "dm") ?: return null
        val dataPart = pkg.targetOf(part, relation) ?: return null
        val drawingPart = pkg.relationshipsOf(dataPart)
            .firstOrNull { it.type.substringAfterLast('/').equals("diagramDrawing", ignoreCase = true) }
            ?.let { pkg.targetOf(dataPart, it.id) }
        val cached = drawingPart?.let { drawingText(pkg, it) }
        return cached?.takeIf { it.isNotBlank() } ?: dataModelText(pkg, dataPart)
    }

    private fun drawingText(pkg: PptxPackage, part: String): String {
        val root = readPart(pkg, part) ?: return ""
        val bodies = mutableListOf<String>()
        collectTextBodies(root, bodies)
        return bodies.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun collectTextBodies(node: XNode, bodies: MutableList<String>) {
        if (node.name.localName == "txBody") {
            bodies += textOfBody(node)
            return
        }
        node.children.forEach { collectTextBodies(it, bodies) }
    }

    private fun dataModelText(pkg: PptxPackage, part: String): String {
        val root = readPart(pkg, part) ?: return ""
        return root.child(DIAGRAM, "ptLst")
            ?.childrenOf(DIAGRAM, "pt")
            .orEmpty()
            .mapNotNull { point -> point.child(DIAGRAM, "t")?.let(::textOfBody)?.takeIf { it.isNotBlank() } }
            .joinToString("\n")
    }

    private fun readPart(pkg: PptxPackage, part: String): XNode? =
        pkg.part(part)?.let { bytes -> runCatching { reader.readTree(bytes.inputStream(), part) }.getOrNull() }

    private fun textOfBody(body: XNode): String = body.childrenOf(OoxmlNamespace.A, "p")
        .map(::textOfParagraph)
        .filter { it.isNotBlank() }
        .joinToString("\n")

    private fun textOfParagraph(paragraph: XNode): String = buildString {
        for (child in paragraph.children) {
            when {
                child.name.matches(OoxmlNamespace.A, "r") || child.name.matches(OoxmlNamespace.A, "fld") ->
                    append(child.child(OoxmlNamespace.A, "t")?.text.orEmpty())
                child.name.matches(OoxmlNamespace.A, "br") -> append('\n')
            }
        }
    }

    private fun addText(lines: MutableList<String>, body: XNode?) {
        body?.let(::textOfBody)?.takeIf { it.isNotBlank() }?.let(lines::add)
    }

    private fun cachedValues(node: XNode): List<String> {
        val values = mutableListOf<String>()
        collectValues(node, values)
        return values
    }

    private fun collectValues(node: XNode, values: MutableList<String>) {
        for (child in node.children) {
            if (child.name.matches(CHART, "pt")) {
                child.child(CHART, "v")?.text?.takeIf { it.isNotBlank() }?.let(values::add)
            } else {
                collectValues(child, values)
            }
        }
    }

    private fun firstDescendant(node: XNode, namespace: String, localName: String): XNode? {
        for (child in node.children) {
            if (child.name.matches(namespace, localName)) return child
            firstDescendant(child, namespace, localName)?.let { return it }
        }
        return null
    }

    private fun isMerged(cell: XNode): Boolean =
        cell.attribute("hMerge") == "1" || cell.attribute("vMerge") == "1"

    private fun isHidden(node: XNode): Boolean {
        val properties = node.children.firstOrNull {
            it.name.namespace == OoxmlNamespace.P && it.name.localName.startsWith("nv")
        } ?: return false
        val property = properties.child(OoxmlNamespace.P, "cNvPr") ?: return false
        return property.attribute("hidden") == "1" || property.attribute("hidden").equals("true", ignoreCase = true)
    }

    private companion object {
        const val CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart"

        const val DIAGRAM = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
    }
}

private class TextCap(private val max: Int) {

    private var used = 0

    fun take(text: String): String? {
        if (text.isBlank() || used >= max) return null
        val kept = text.take(max - used)
        used += kept.length
        return kept.takeIf { it.isNotBlank() }
    }
}
