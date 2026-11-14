package io.github.pixelrick420.papyrus.viewer.pptx

/**
 * A shape's inline `a:custGeom`, read into the same [PresetShape] the preset table yields. A geometry over a
 * cap, or malformed, is [Invalid] so the caller drops the whole shape instead of drawing part of it.
 */
internal sealed interface CustomGeometry {
    object Absent : CustomGeometry

    class Shape(val shape: PresetShape) : CustomGeometry

    object Invalid : CustomGeometry

    companion object {
        fun read(properties: XNode?, limits: PptxLimits = PptxLimits()): CustomGeometry {
            val element = properties?.child(OoxmlNamespace.A, "custGeom") ?: return Absent
            return runCatching { parse(element, limits) }.getOrElse { Invalid }
        }

        private fun parse(element: XNode, limits: PptxLimits): CustomGeometry {
            val adjusts = guides(element.child(OoxmlNamespace.A, "avLst")) ?: return Invalid
            val guides = guides(element.child(OoxmlNamespace.A, "gdLst")) ?: return Invalid
            if (adjusts.size + guides.size > limits.maxGuideEvaluations) return Invalid
            val paths = element.child(OoxmlNamespace.A, "pathLst")
                ?.childrenOf(OoxmlNamespace.A, "path")
                ?: return Invalid
            if (paths.size > limits.maxPaths) return Invalid
            if (paths.sumOf { it.children.size } > limits.maxPathCommands) return Invalid
            val read = paths.map { path(it) ?: return Invalid }
            val rect = element.child(OoxmlNamespace.A, "rect")?.let { rect(it) ?: return Invalid }
            return Shape(PresetShape(NAME, adjusts, guides, read, rect))
        }

        private fun guides(list: XNode?): List<Guide>? {
            if (list == null) return emptyList()
            val found = mutableListOf<Guide>()
            for (node in list.childrenOf(OoxmlNamespace.A, "gd")) {
                val name = node.attribute("name") ?: return null
                val formula = node.attribute("fmla") ?: return null
                if (!finiteFormula(formula)) return null
                found += Guide(name, formula)
            }
            return found
        }

        private fun finiteFormula(formula: String): Boolean =
            formula.split(WHITESPACE).all { token -> token.toDoubleOrNull()?.isFinite() != false }

        private fun path(node: XNode): PresetPath? {
            if (!finiteNumber(node.attribute("w")) || !finiteNumber(node.attribute("h"))) return null
            val commands = mutableListOf<PresetCommand>()
            for (child in node.children) commands += command(child) ?: return null
            return PresetPath(
                width = node.attribute("w")?.toDouble(),
                height = node.attribute("h")?.toDouble(),
                fill = PresetFillMode.of(node.attribute("fill")),
                stroke = node.attribute("stroke")?.let { it != "false" && it != "0" } ?: true,
                commands = commands,
            )
        }

        private fun command(node: XNode): PresetCommand? = when (node.name) {
            OoxmlName(OoxmlNamespace.A, "moveTo") -> point(node)?.let { PresetCommand.MoveTo(it.first, it.second) }
            OoxmlName(OoxmlNamespace.A, "lnTo") -> point(node)?.let { PresetCommand.LineTo(it.first, it.second) }
            OoxmlName(OoxmlNamespace.A, "quadBezTo") -> points(node, 2)?.let {
                PresetCommand.QuadTo(it[0].first, it[0].second, it[1].first, it[1].second)
            }
            OoxmlName(OoxmlNamespace.A, "cubicBezTo") -> points(node, 3)?.let {
                PresetCommand.CubicTo(
                    it[0].first, it[0].second,
                    it[1].first, it[1].second,
                    it[2].first, it[2].second,
                )
            }
            OoxmlName(OoxmlNamespace.A, "arcTo") -> arc(node)
            OoxmlName(OoxmlNamespace.A, "close") -> PresetCommand.Close
            else -> null
        }

        private fun point(node: XNode): Pair<String, String>? = points(node, 1)?.first()

        private fun points(node: XNode, count: Int): List<Pair<String, String>>? {
            val elements = node.childrenOf(OoxmlNamespace.A, "pt")
            if (elements.size != count) return null
            val points = mutableListOf<Pair<String, String>>()
            for (element in elements) {
                val x = element.attribute("x") ?: return null
                val y = element.attribute("y") ?: return null
                if (!finiteToken(x) || !finiteToken(y)) return null
                points += x to y
            }
            return points
        }

        private fun arc(node: XNode): PresetCommand? {
            val widthRadius = node.attribute("wR") ?: return null
            val heightRadius = node.attribute("hR") ?: return null
            val startAngle = node.attribute("stAng") ?: return null
            val swingAngle = node.attribute("swAng") ?: return null
            if (listOf(widthRadius, heightRadius, startAngle, swingAngle).any { !finiteToken(it) }) return null
            val swing = swingAngle.toDoubleOrNull()
            if (swing != null && !ArcMath.isSweepWithinCap(swing)) return null
            return PresetCommand.ArcTo(widthRadius, heightRadius, startAngle, swingAngle)
        }

        private fun rect(node: XNode): PresetTextRect? {
            val left = node.attribute("l") ?: "l"
            val top = node.attribute("t") ?: "t"
            val right = node.attribute("r") ?: "r"
            val bottom = node.attribute("b") ?: "b"
            if (listOf(left, top, right, bottom).any { !finiteToken(it) }) return null
            return PresetTextRect(left, top, right, bottom)
        }

        /** A guide formula may name other guides, but a numeric literal in it must be a real number. */
        private fun finiteToken(token: String): Boolean = token.toDoubleOrNull()?.isFinite() != false

        private fun finiteNumber(value: String?): Boolean =
            value == null || value.toDoubleOrNull()?.isFinite() == true

        private const val NAME = "custGeom"

        private val WHITESPACE = Regex("\\s+")
    }
}
