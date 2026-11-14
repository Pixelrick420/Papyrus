package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import kotlin.math.roundToInt

/** One command of a preset path; each coordinate is a guide name or a number in the path's own space. */
internal sealed interface PresetCommand {
    data class MoveTo(val x: String, val y: String) : PresetCommand

    data class LineTo(val x: String, val y: String) : PresetCommand

    data class QuadTo(val controlX: String, val controlY: String, val x: String, val y: String) :
        PresetCommand

    data class CubicTo(
        val control1X: String,
        val control1Y: String,
        val control2X: String,
        val control2Y: String,
        val x: String,
        val y: String,
    ) : PresetCommand

    data class ArcTo(
        val widthRadius: String,
        val heightRadius: String,
        val startAngle: String,
        val swingAngle: String,
    ) : PresetCommand

    object Close : PresetCommand
}

/** How a path's fill relates to the shape's own fill: unfilled, unchanged, or tinted/shaded. */
internal enum class PresetFillMode(private val tint: Double, private val shade: Double) {
    NORM(0.0, 0.0),
    NONE(0.0, 0.0),
    LIGHTEN(0.4, 0.0),
    LIGHTEN_LESS(0.2, 0.0),
    DARKEN(0.0, 0.4),
    DARKEN_LESS(0.0, 0.2),
    ;

    /** The fill colour for a shape filled with [color], or null when this path is not filled. */
    fun adjust(color: Rgba): Rgba? {
        if (this == NONE) return null
        return Rgba(channel(color.red), channel(color.green), channel(color.blue), color.alpha)
    }

    private fun channel(value: Int): Int =
        ((value + (255 - value) * tint) * (1 - shade)).roundToInt().coerceIn(0, 255)

    companion object {
        fun of(name: String?): PresetFillMode = when (name) {
            "none" -> NONE
            "lighten" -> LIGHTEN
            "lightenLess" -> LIGHTEN_LESS
            "darken" -> DARKEN
            "darkenLess" -> DARKEN_LESS
            else -> NORM
        }
    }
}

/** The `a:rect` text rectangle as four guide formulas, evaluated once the shape size is known. */
internal class PresetTextRect(
    val left: String,
    val top: String,
    val right: String,
    val bottom: String,
)

/** One `a:path`: its own coordinate space when it declares one, how it paints, and its commands. */
internal class PresetPath(
    val width: Double?,
    val height: Double?,
    val fill: PresetFillMode,
    val stroke: Boolean,
    val commands: List<PresetCommand>,
)

/** One preset's rendering data: adjust defaults, guides, paths and the optional text rectangle. */
internal class PresetShape(
    val name: String,
    val adjusts: List<Guide>,
    val guides: List<Guide>,
    val paths: List<PresetPath>,
    val textRect: PresetTextRect?,
)

/**
 * Reads preset shapes from `presetShapeDefinitions.xml`, parsing only the parts rendering needs
 * (`avLst`, `gdLst`, `pathLst`, `rect`) and only the shape asked for, then caching it.
 */
internal class PresetGeometry(
    /** Injected because the file is an asset in the app and a plain file in tests. */
    private val open: () -> InputStream,
    /** Injected because [Xml.newPullParser] is a platform stub that returns null in JVM unit tests. */
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val cache = HashMap<String, PresetShape?>()

    @Synchronized
    fun shape(name: String): PresetShape? {
        if (cache.containsKey(name)) return cache[name]
        return parse(name).also { cache[name] = it }
    }

    private fun parse(name: String): PresetShape? {
        open().use { input ->
            val parser = newParserFactory().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(input, null)
            }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                val found = parser.eventType == XmlPullParser.START_TAG &&
                    parser.depth == PRESET_DEPTH &&
                    parser.name == name
                if (found) return readShape(parser, name)
            }
        }
        return null
    }

    private fun readShape(parser: XmlPullParser, name: String): PresetShape {
        var adjusts = emptyList<Guide>()
        var guides = emptyList<Guide>()
        var paths = emptyList<PresetPath>()
        var textRect: PresetTextRect? = null
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "avLst" -> adjusts = readGuides(parser)
                "gdLst" -> guides = readGuides(parser)
                "pathLst" -> paths = readPaths(parser)
                "rect" -> textRect = PresetTextRect(
                    left = parser.getAttributeValue(null, "l") ?: "l",
                    top = parser.getAttributeValue(null, "t") ?: "t",
                    right = parser.getAttributeValue(null, "r") ?: "r",
                    bottom = parser.getAttributeValue(null, "b") ?: "b",
                )
                else -> skipSubtree(parser)
            }
        }
        return PresetShape(name, adjusts, guides, paths, textRect)
    }

    private fun readGuides(parser: XmlPullParser): List<Guide> {
        val depth = parser.depth
        val guides = mutableListOf<Guide>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name != "gd") {
                skipSubtree(parser)
                continue
            }
            val name = parser.getAttributeValue(null, "name")
            val formula = parser.getAttributeValue(null, "fmla")
            if (name != null && formula != null) guides += Guide(name, formula)
        }
        return guides
    }

    private fun readPaths(parser: XmlPullParser): List<PresetPath> {
        val depth = parser.depth
        val paths = mutableListOf<PresetPath>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name != "path" || paths.size >= limits.maxPaths) {
                skipSubtree(parser)
                continue
            }
            paths += readPath(parser)
        }
        return paths
    }

    private fun readPath(parser: XmlPullParser): PresetPath {
        val width = parser.getAttributeValue(null, "w")?.toDoubleOrNull()
        val height = parser.getAttributeValue(null, "h")?.toDoubleOrNull()
        val fill = PresetFillMode.of(parser.getAttributeValue(null, "fill"))
        val stroke = parser.getAttributeValue(null, "stroke")?.let { it != "false" && it != "0" } ?: true
        val depth = parser.depth
        val commands = mutableListOf<PresetCommand>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (commands.size >= limits.maxPathCommands) {
                skipSubtree(parser)
                continue
            }
            command(parser)?.let { commands += it }
        }
        return PresetPath(width, height, fill, stroke, commands)
    }

    private fun command(parser: XmlPullParser): PresetCommand? = when (parser.name) {
        "moveTo" -> readPoints(parser).firstOrNull()?.let { PresetCommand.MoveTo(it.first, it.second) }
        "lnTo" -> readPoints(parser).firstOrNull()?.let { PresetCommand.LineTo(it.first, it.second) }
        "quadBezTo" -> readPoints(parser).takeIf { it.size >= 2 }?.let {
            PresetCommand.QuadTo(it[0].first, it[0].second, it[1].first, it[1].second)
        }
        "cubicBezTo" -> readPoints(parser).takeIf { it.size >= 3 }?.let {
            PresetCommand.CubicTo(
                it[0].first, it[0].second,
                it[1].first, it[1].second,
                it[2].first, it[2].second,
            )
        }
        "arcTo" -> readArc(parser)
        "close" -> PresetCommand.Close
        else -> {
            skipSubtree(parser)
            null
        }
    }

    private fun readArc(parser: XmlPullParser): PresetCommand? {
        val widthRadius = parser.getAttributeValue(null, "wR")
        val heightRadius = parser.getAttributeValue(null, "hR")
        val startAngle = parser.getAttributeValue(null, "stAng")
        val swingAngle = parser.getAttributeValue(null, "swAng")
        if (widthRadius == null || heightRadius == null || startAngle == null || swingAngle == null) {
            skipSubtree(parser)
            return null
        }
        return PresetCommand.ArcTo(widthRadius, heightRadius, startAngle, swingAngle)
    }

    private fun readPoints(parser: XmlPullParser): List<Pair<String, String>> {
        val depth = parser.depth
        val points = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "pt") continue
            val x = parser.getAttributeValue(null, "x")
            val y = parser.getAttributeValue(null, "y")
            if (x != null && y != null) points += x to y
        }
        return points
    }

    private fun skipSubtree(parser: XmlPullParser) {
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    private companion object {
        /** The root element holds one `<preset>` per shape, so a preset sits at depth two. */
        const val PRESET_DEPTH = 2
    }
}
