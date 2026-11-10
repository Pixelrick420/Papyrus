package com.papyrus.app.viewer.svg

import com.papyrus.app.viewer.ParserFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class SvgLimits(
    val maxElements: Int = 20_000,
    val maxOps: Int = 50_000,
    val maxDepth: Int = 200,
)

internal fun interface SvgAttributeReader {
    fun attribute(name: String): String?
}

fun interface SvgRasteriser {
    fun rasterise(scene: SvgScene): ByteArray?
}

sealed interface PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp
    data class LineTo(val x: Float, val y: Float) : PathOp
    data object Close : PathOp
}

class SvgShape(val ops: List<PathOp>, val closed: Boolean, val fill: Int?, val stroke: Int?, val strokeWidth: Float)

// [tintable] is true when no shape named a colour of its own, so the viewer may draw it in the text colour.
class SvgScene(val width: Float, val height: Float, val shapes: List<SvgShape>, val tintable: Boolean) {
    val aspect: Float get() = width / height
}

private const val CIRCLE_SEGMENTS = 32
private const val DEFAULT_SIZE = 1f
private const val GREY = 0x808080

private class Paint(val rgb: Int?, val explicit: Boolean)

private val NO_PAINT = Paint(null, false)
private val BLACK_BY_DEFAULT = Paint(0, false)

private class SvgFrame(
    val tx: Float,
    val ty: Float,
    val fill: Paint,
    val stroke: Paint,
    val strokeWidth: Float,
    val skipped: Boolean,
)

private val SKIPPED_SUBTREES = setOf(
    "defs", "title", "desc", "metadata", "clipPath", "mask", "style", "text", "foreignObject", "symbol", "pattern",
    "marker", "linearGradient", "radialGradient", "filter", "image", "switch",
)

private val NAMED_COLORS = mapOf(
    "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "lime" to 0x00FF00,
    "blue" to 0x0000FF, "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "magenta" to 0xFF00FF,
    "fuchsia" to 0xFF00FF, "gray" to GREY, "grey" to GREY, "silver" to 0xC0C0C0, "maroon" to 0x800000,
    "olive" to 0x808000, "navy" to 0x000080, "purple" to 0x800080, "teal" to 0x008080, "orange" to 0xFFA500,
)

// Whatever it cannot draw faithfully (a curve, a rotation, text, a gradient) is dropped whole, never half drawn. Past [maxElements] or [maxOps] it stops growing; nothing throws.
internal class SvgSceneBuilder(
    private val maxElements: Int = SvgLimits().maxElements,
    private val maxOps: Int = SvgLimits().maxOps,
    private val maxDepth: Int = SvgLimits().maxDepth,
) {
    private val stack = ArrayList<SvgFrame>()
    private val shapes = ArrayList<SvgShape>()
    private var elements = 0
    private var opCount = 0
    private var colour = false
    private var rootSeen = false
    private var minX = 0f
    private var minY = 0f
    private var width: Float? = null
    private var height: Float? = null

    fun start(rawName: String, attrs: SvgAttributeReader): Boolean {
        val name = rawName.substringAfter(':')
        val parent = stack.lastOrNull()
        elements++
        val keep = (parent == null || !parent.skipped) && elements <= maxElements && stack.size < maxDepth &&
            name !in SKIPPED_SUBTREES && !hidden(attrs)
        val translate = if (keep) translateOf(attrs.attribute("transform")) else null
        if (!keep || translate == null) {
            stack += SvgFrame(0f, 0f, NO_PAINT, NO_PAINT, 0f, skipped = true)
            return false
        }
        val styles = styleOf(attrs.attribute("style"))
        val fill = paintOf(styles["fill"] ?: attrs.attribute("fill"), parent?.fill ?: BLACK_BY_DEFAULT)
        val stroke = paintOf(styles["stroke"] ?: attrs.attribute("stroke"), parent?.stroke ?: NO_PAINT)
        val strokeWidth = (styles["stroke-width"] ?: attrs.attribute("stroke-width"))?.let(::lengthOf)
            ?: parent?.strokeWidth
            ?: 1f
        val tx = (parent?.tx ?: 0f) + translate.first
        val ty = (parent?.ty ?: 0f) + translate.second
        stack += SvgFrame(tx, ty, fill, stroke, strokeWidth, skipped = false)
        if (name == "svg" && !rootSeen) readRoot(attrs)
        draw(name, attrs, stack.last())
        return true
    }

    fun end(@Suppress("UNUSED_PARAMETER") name: String) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** Text is not drawn: its glyphs are not shapes, and its words are already in the text flow. */
    fun text(@Suppress("UNUSED_PARAMETER") value: String) = Unit

    fun scene(): SvgScene? {
        if (shapes.isEmpty()) return null
        val w = width ?: (shapes.maxOf { s -> s.ops.maxOf { op -> xOf(op) } }.coerceAtLeast(DEFAULT_SIZE))
        val h = height ?: (shapes.maxOf { s -> s.ops.maxOf { op -> yOf(op) } }.coerceAtLeast(DEFAULT_SIZE))
        return SvgScene(w, h, shapes.toList(), tintable = !colour)
    }

    private fun xOf(op: PathOp): Float = when (op) {
        is PathOp.MoveTo -> op.x
        is PathOp.LineTo -> op.x
        PathOp.Close -> 0f
    }

    private fun yOf(op: PathOp): Float = when (op) {
        is PathOp.MoveTo -> op.y
        is PathOp.LineTo -> op.y
        PathOp.Close -> 0f
    }

    private fun readRoot(attrs: SvgAttributeReader) {
        rootSeen = true
        val box = attrs.attribute("viewBox")?.split(' ', ',', '\t', '\n', '\r')?.filter { it.isNotEmpty() }?.mapNotNull { it.toFloatOrNull() }
        if (box != null && box.size == VIEWBOX_PARTS && box[2] > 0f && box[3] > 0f) {
            minX = box[0]
            minY = box[1]
            width = box[2]
            height = box[3]
            return
        }
        width = attrs.attribute("width")?.let(::lengthOf)
        height = attrs.attribute("height")?.let(::lengthOf)
        // One side alone says nothing about the shape; both come from what is drawn.
        if (width == null || height == null) {
            width = null
            height = null
        }
    }

    private fun draw(name: String, attrs: SvgAttributeReader, frame: SvgFrame) {
        val ox = frame.tx - minX
        val oy = frame.ty - minY
        val ops: List<PathOp>? = when (name) {
            "rect" -> rectOps(attrs, ox, oy)
            "line" -> lineOps(attrs, ox, oy)
            "polyline", "polygon" -> pointOps(attrs.attribute("points"), ox, oy, close = name == "polygon")
            "circle" -> ellipseOps(attrs, "r", "r", ox, oy)
            "ellipse" -> ellipseOps(attrs, "rx", "ry", ox, oy)
            "path" -> attrs.attribute("d")?.let { pathOps(it, ox, oy) }
            else -> null
        }
        if (ops.isNullOrEmpty()) return
        val fill = frame.fill.takeIf { name != "line" }?.rgb
        val stroke = frame.stroke.rgb
        if (fill == null && stroke == null) return
        if (opCount + ops.size > maxOps) {
            // Past the cap the scene stops growing; later elements may still be small enough to fit, so keep going.
            return
        }
        opCount += ops.size
        if ((fill != null && frame.fill.explicit && name != "line") || (stroke != null && frame.stroke.explicit)) colour = true
        shapes += SvgShape(ops, closed = ops.last() == PathOp.Close, fill = fill, stroke = stroke, strokeWidth = frame.strokeWidth)
    }

    private fun number(attrs: SvgAttributeReader, name: String): Float = attrs.attribute(name)?.let(::lengthOf) ?: 0f

    private fun rectOps(attrs: SvgAttributeReader, ox: Float, oy: Float): List<PathOp>? {
        val w = number(attrs, "width")
        val h = number(attrs, "height")
        if (w <= 0f || h <= 0f) return null
        val x = number(attrs, "x") + ox
        val y = number(attrs, "y") + oy
        return listOf(PathOp.MoveTo(x, y), PathOp.LineTo(x + w, y), PathOp.LineTo(x + w, y + h), PathOp.LineTo(x, y + h), PathOp.Close)
    }

    private fun lineOps(attrs: SvgAttributeReader, ox: Float, oy: Float): List<PathOp> = listOf(
        PathOp.MoveTo(number(attrs, "x1") + ox, number(attrs, "y1") + oy),
        PathOp.LineTo(number(attrs, "x2") + ox, number(attrs, "y2") + oy),
    )

    private fun ellipseOps(attrs: SvgAttributeReader, rx: String, ry: String, ox: Float, oy: Float): List<PathOp>? {
        val a = number(attrs, rx)
        val b = number(attrs, ry)
        if (a <= 0f || b <= 0f) return null
        val cx = number(attrs, "cx") + ox
        val cy = number(attrs, "cy") + oy
        val ops = ArrayList<PathOp>(CIRCLE_SEGMENTS + 2)
        for (k in 0 until CIRCLE_SEGMENTS) {
            val angle = 2.0 * PI * k / CIRCLE_SEGMENTS
            val x = cx + a * cos(angle).toFloat()
            val y = cy + b * sin(angle).toFloat()
            ops += if (k == 0) PathOp.MoveTo(x, y) else PathOp.LineTo(x, y)
        }
        ops += PathOp.Close
        return ops
    }

    private fun pointOps(points: String?, ox: Float, oy: Float, close: Boolean): List<PathOp>? {
        val values = points?.split(' ', ',', '\t', '\n', '\r')
            ?.filter { it.isNotEmpty() }
            ?.map { it.toFloatOrNull() ?: return null }
            ?: return null
        if (values.size < 4) return null
        val ops = ArrayList<PathOp>(values.size / 2 + 1)
        for (k in 0 until values.size / 2) {
            val x = values[2 * k] + ox
            val y = values[2 * k + 1] + oy
            ops += if (k == 0) PathOp.MoveTo(x, y) else PathOp.LineTo(x, y)
        }
        if (close) ops += PathOp.Close
        return ops
    }

    /** Straight segments only; a curve or arc anywhere in the path drops all of it. */
    private fun pathOps(d: String, ox: Float, oy: Float): List<PathOp>? {
        val ops = ArrayList<PathOp>()
        var i = 0
        var command = ' '
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f

        fun skipSeparators() {
            while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++
        }

        fun read(): Float? {
            skipSeparators()
            val start = i
            if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
            while (i < d.length && (d[i].isDigit() || d[i] == '.')) i++
            if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                i++
                if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
                while (i < d.length && d[i].isDigit()) i++
            }
            return d.substring(start, i).toFloatOrNull()
        }

        while (true) {
            skipSeparators()
            if (i >= d.length) break
            if (d[i].isLetter()) {
                command = d[i++]
                when (command) {
                    'Z', 'z' -> {
                        if (ops.isEmpty()) return null
                        ops += PathOp.Close
                        cx = sx
                        cy = sy
                    }
                    'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v' -> if (ops.isEmpty() && command !in "Mm") return null
                    else -> return null
                }
                continue
            }
            val relative = command.isLowerCase()
            when (command) {
                'M', 'm', 'L', 'l' -> {
                    val x = read() ?: return null
                    val y = read() ?: return null
                    cx = if (relative) cx + x else x
                    cy = if (relative) cy + y else y
                    if (command == 'M' || command == 'm') {
                        ops += PathOp.MoveTo(cx + ox, cy + oy)
                        sx = cx
                        sy = cy
                        command = if (relative) 'l' else 'L'
                    } else {
                        ops += PathOp.LineTo(cx + ox, cy + oy)
                    }
                }
                'H', 'h' -> {
                    val x = read() ?: return null
                    cx = if (relative) cx + x else x
                    ops += PathOp.LineTo(cx + ox, cy + oy)
                }
                'V', 'v' -> {
                    val y = read() ?: return null
                    cy = if (relative) cy + y else y
                    ops += PathOp.LineTo(cx + ox, cy + oy)
                }
                else -> return null
            }
        }
        return ops.takeIf { it.isNotEmpty() }
    }

    private fun hidden(attrs: SvgAttributeReader): Boolean =
        attrs.attribute("display")?.trim() == "none" || styleOf(attrs.attribute("style"))["display"] == "none"

    private fun translateOf(transform: String?): Pair<Float, Float>? {
        val text = transform?.trim().orEmpty()
        if (text.isEmpty()) return 0f to 0f
        if (!text.startsWith("translate(") || !text.endsWith(")")) return null
        val inside = text.substring("translate(".length, text.length - 1)
        if ('(' in inside || ')' in inside) return null
        val parts = inside.split(' ', ',', '\t', '\n', '\r').filter { it.isNotEmpty() }.map { it.toFloatOrNull() ?: return null }
        return when (parts.size) {
            1 -> parts[0] to 0f
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun styleOf(style: String?): Map<String, String> {
        if (style.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (declaration in style.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon > 0) out[declaration.substring(0, colon).trim()] = declaration.substring(colon + 1).trim()
        }
        return out
    }

    private fun lengthOf(raw: String): Float? = raw.trim().removeSuffix("px").toFloatOrNull()

    private fun paintOf(value: String?, inherited: Paint): Paint {
        val text = value?.trim()?.lowercase()
        return when {
            text.isNullOrEmpty() || text == "inherit" -> inherited
            text == "none" || text == "transparent" -> NO_PAINT
            text == "currentcolor" -> BLACK_BY_DEFAULT
            else -> Paint(colorOf(text) ?: GREY, explicit = true)
        }
    }

    private fun colorOf(text: String): Int? = when {
        text.startsWith("#") -> hexOf(text.substring(1))
        text.startsWith("rgb(") && text.endsWith(")") -> rgbOf(text.substring(4, text.length - 1))
        else -> NAMED_COLORS[text]
    }

    private fun hexOf(hex: String): Int? = when (hex.length) {
        3 -> hex.toIntOrNull(HEX)?.let { v -> ((v shr 8) and 0xF) * 0x110000 + ((v shr 4) and 0xF) * 0x1100 + (v and 0xF) * 0x11 }
        6 -> hex.toIntOrNull(HEX)
        else -> null
    }

    private fun rgbOf(inside: String): Int? {
        val parts = inside.split(',', ' ').filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val channels = parts.map { part ->
            val percent = part.endsWith("%")
            val v = part.removeSuffix("%").toFloatOrNull() ?: return null
            (if (percent) v * 255f / 100f else v).toInt().coerceIn(0, 255)
        }
        return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    private companion object {
        const val HEX = 16
        const val VIEWBOX_PARTS = 4
    }
}

@Throws(XmlPullParserException::class)
internal fun parseSvg(bytes: ByteArray, factory: ParserFactory, limits: SvgLimits = SvgLimits()): SvgScene? {
    val parser = factory(bytes.inputStream())
    val builder = SvgSceneBuilder(limits.maxElements, limits.maxOps, limits.maxDepth)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> builder.start(parser.name) { name -> parser.getAttributeValue(null, name) }
            XmlPullParser.END_TAG -> builder.end(parser.name)
        }
        event = parser.next()
    }
    return builder.scene()
}
