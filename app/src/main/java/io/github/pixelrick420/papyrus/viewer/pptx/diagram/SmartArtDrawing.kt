package io.github.pixelrick420.papyrus.viewer.pptx.diagram

import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlName
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFills
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePreset
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/** The theme-resolved colours and paint lookups the cached drawing's `dsp` shapes need. */
internal class SmartArtStyle(
    val text: Rgba,
    val formatOf: (XNode?, XNode?) -> SceneFormat?,
)

/** One `dsp:txBody` and the frame PowerPoint laid it out in, both in slide coordinates. */
internal class SmartArtText(val body: XNode, val frame: SceneRect)

/** PowerPoint's own last layout of a SmartArt, painted without re-running any layout algorithm. */
internal class SmartArtDrawing(
    val nodes: List<SceneNode> = emptyList(),
    val texts: List<SmartArtText> = emptyList(),
    val nodeText: List<String> = emptyList(),
) {

    companion object {

        private const val MAX_GROUP_DEPTH = 8
        private const val LABEL = "SmartArt not displayed"
        private const val FALLBACK_LINE_EMU = 25_400.0

        fun fallback(bounds: SceneRect, style: SmartArtStyle, nodeText: List<String>): SmartArtDrawing {
            val placeholder = SceneShape(
                id = null,
                name = LABEL,
                frame = bounds,
                preset = ScenePreset("rect"),
                format = SceneFormat(
                    fill = ScenePaint.None,
                    line = SceneLine(FALLBACK_LINE_EMU, ScenePaint.Solid(style.text), "dash"),
                    effects = null,
                ),
            )
            val label = listOf(LABEL) + nodeText
            val frame = SceneRect(
                bounds.x + bounds.width * 0.1,
                bounds.y + bounds.height * 0.2,
                bounds.width * 0.8,
                (bounds.height * 0.6).coerceAtLeast(0.0),
            )
            return SmartArtDrawing(listOf(placeholder), listOf(SmartArtText(textBodyOf(label), frame)), nodeText)
        }

        private fun textBodyOf(lines: List<String>): XNode {
            val body = XNode(OoxmlName(OoxmlNamespace.DSP, "txBody"))
            body.children += XNode(OoxmlName(OoxmlNamespace.A, "bodyPr"))
            for (line in lines) {
                val text = XNode(OoxmlName(OoxmlNamespace.A, "t"))
                text.text = line
                val run = XNode(OoxmlName(OoxmlNamespace.A, "r"))
                run.children += text
                val paragraph = XNode(OoxmlName(OoxmlNamespace.A, "p"))
                paragraph.children += run
                body.children += paragraph
            }
            return body
        }

        fun read(
            root: XNode?,
            part: String,
            style: SmartArtStyle,
            origin: SceneRect,
            nodeText: List<String> = emptyList(),
        ): SmartArtDrawing? {
            val tree = root?.child(OoxmlNamespace.DSP, "spTree") ?: return null
            val texts = ArrayList<SmartArtText>()
            val nodes = tree.children.mapNotNull { parse(it, part, style, origin, 0, texts) }
            if (nodes.isEmpty()) return null
            return SmartArtDrawing(nodes, texts, nodeText)
        }

        private fun parse(
            node: XNode,
            part: String,
            style: SmartArtStyle,
            origin: SceneRect,
            depth: Int,
            texts: MutableList<SmartArtText>,
        ): SceneNode? {
            if (depth > MAX_GROUP_DEPTH) return null
            return when {
                node.name.matches(OoxmlNamespace.DSP, "sp") -> shape(node, part, style, origin, texts)
                node.name.matches(OoxmlNamespace.DSP, "pic") -> picture(node, part, origin)
                node.name.matches(OoxmlNamespace.DSP, "grpSp") -> group(node, part, style, origin, depth, texts)
                else -> null
            }
        }

        private fun shape(
            node: XNode,
            part: String,
            style: SmartArtStyle,
            origin: SceneRect,
            texts: MutableList<SmartArtText>,
        ): SceneShape? {
            val properties = node.child(OoxmlNamespace.DSP, "spPr")
            val transform = properties?.child(OoxmlNamespace.A, "xfrm")
            val frame = rectOf(transform, origin) ?: return null
            val body = node.child(OoxmlNamespace.DSP, "txBody")
            if (body != null) {
                val textTransform = node.child(OoxmlNamespace.DSP, "txXfrm")
                texts += SmartArtText(body, rectOf(textTransform, origin) ?: frame)
            }
            val props = node.child(OoxmlNamespace.DSP, "nvSpPr")?.child(OoxmlNamespace.DSP, "cNvPr")
            return SceneShape(
                id = props?.attribute("id")?.toIntOrNull(),
                name = props?.attribute("name"),
                frame = frame,
                preset = presetOf(properties, transform),
                textBody = body,
                transform = transform,
                properties = properties,
                format = style.formatOf(properties, node.child(OoxmlNamespace.DSP, "style")),
                sourcePart = part,
            )
        }

        private fun picture(node: XNode, part: String, origin: SceneRect): ScenePicture? {
            val properties = node.child(OoxmlNamespace.DSP, "spPr")
            val transform = properties?.child(OoxmlNamespace.A, "xfrm")
            val props = node.child(OoxmlNamespace.DSP, "nvPicPr")?.child(OoxmlNamespace.DSP, "cNvPr")
            val source = PictureFills.read(node.child(OoxmlNamespace.DSP, "blipFill"), part) ?: return null
            return ScenePicture(
                id = props?.attribute("id")?.toIntOrNull(),
                name = props?.attribute("name"),
                frame = rectOf(transform, origin) ?: return null,
                relationshipId = source.relationshipId,
                transform = transform,
                properties = properties,
                source = source,
                mask = presetOf(properties, transform),
                sourcePart = part,
            )
        }

        private fun group(
            node: XNode,
            part: String,
            style: SmartArtStyle,
            origin: SceneRect,
            depth: Int,
            texts: MutableList<SmartArtText>,
        ): SceneGroup {
            val transform = node.child(OoxmlNamespace.DSP, "grpSpPr")?.child(OoxmlNamespace.A, "xfrm")
            val props = node.child(OoxmlNamespace.DSP, "nvGrpSpPr")?.child(OoxmlNamespace.DSP, "cNvPr")
            return SceneGroup(
                id = props?.attribute("id")?.toIntOrNull(),
                name = props?.attribute("name"),
                frame = rectOf(transform, origin),
                childFrame = childRectOf(transform, origin),
                children = node.children.mapNotNull { parse(it, part, style, origin, depth + 1, texts) },
                rotation = transform?.attribute("rot")?.toDoubleOrNull() ?: 0.0,
                flipH = transform?.attribute("flipH") == "1",
                flipV = transform?.attribute("flipV") == "1",
                sourcePart = part,
            )
        }

        private fun rectOf(transform: XNode?, origin: SceneRect): SceneRect? {
            transform ?: return null
            val offset = transform.child(OoxmlNamespace.A, "off") ?: return null
            val extent = transform.child(OoxmlNamespace.A, "ext") ?: return null
            val x = offset.attribute("x")?.toDoubleOrNull() ?: return null
            val y = offset.attribute("y")?.toDoubleOrNull() ?: return null
            val width = extent.attribute("cx")?.toDoubleOrNull() ?: return null
            val height = extent.attribute("cy")?.toDoubleOrNull() ?: return null
            return SceneRect(x + origin.x, y + origin.y, width, height)
        }

        private fun childRectOf(transform: XNode?, origin: SceneRect): SceneRect? {
            transform ?: return null
            val offset = transform.child(OoxmlNamespace.A, "chOff") ?: return null
            val extent = transform.child(OoxmlNamespace.A, "chExt") ?: return null
            val x = offset.attribute("x")?.toDoubleOrNull() ?: return null
            val y = offset.attribute("y")?.toDoubleOrNull() ?: return null
            val width = extent.attribute("cx")?.toDoubleOrNull() ?: return null
            val height = extent.attribute("cy")?.toDoubleOrNull() ?: return null
            return SceneRect(x + origin.x, y + origin.y, width, height)
        }

        private fun presetOf(properties: XNode?, transform: XNode?): ScenePreset? {
            val geometry = properties?.child(OoxmlNamespace.A, "prstGeom") ?: return null
            return ScenePreset(
                preset = geometry.attribute("prst"),
                flipH = transform?.attribute("flipH") == "1",
                flipV = transform?.attribute("flipV") == "1",
            )
        }
    }
}
