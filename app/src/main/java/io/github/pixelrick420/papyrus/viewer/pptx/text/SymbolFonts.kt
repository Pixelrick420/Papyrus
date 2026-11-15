package io.github.pixelrick420.papyrus.viewer.pptx.text

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A character of one of the symbol families the deck formats use for bullets, resolved to something
 * drawable.
 *
 * The mapping table follows the Unicode WG2 proposal that encoded these sets (n4022, n4384): most
 * characters map to a Unicode code point, and the ones whose code point sits in the legacy-computing
 * block, which installed Android fonts rarely cover, also carry vector geometry as a fallback.
 */
internal sealed interface SymbolGlyph {

    class Text(val text: String) : SymbolGlyph

    class Vector(val shape: SymbolShape, val fallback: String? = null) : SymbolGlyph
}

/** One glyph's vector geometry in a unit box: `(0, 0)` is the top left corner and `(1, 1)` the bottom right. */
internal class SymbolShape(val paths: List<SymbolPath>)

internal class SymbolPath(
    val segments: List<SymbolSegment>,
    val opacity: Double = 1.0,
    val evenOdd: Boolean = false,
)

internal sealed interface SymbolSegment {
    data class MoveTo(val x: Double, val y: Double) : SymbolSegment

    data class LineTo(val x: Double, val y: Double) : SymbolSegment

    data class CubicTo(
        val control1X: Double,
        val control1Y: Double,
        val control2X: Double,
        val control2Y: Double,
        val x: Double,
        val y: Double,
    ) : SymbolSegment

    data object Close : SymbolSegment
}

/** Resolves a symbol family's character code to the Unicode glyph or the vector shape that replaces it. */
internal class SymbolFonts {

    /** Null when [font] is not a symbol family, or the character has no mapping in it. */
    fun glyph(font: String?, char: String): SymbolGlyph? {
        val family = familyOf(font) ?: return null
        val code = char.firstOrNull()?.code ?: return null
        VECTOR_GLYPHS[family]?.get(code)?.let { shape ->
            return SymbolGlyph.Vector(shape, UNICODE_GLYPHS[family]?.get(code)?.let(::codePointToString))
        }
        return UNICODE_GLYPHS[family]?.get(code)?.let { SymbolGlyph.Text(codePointToString(it)) }
    }
}

private val FAMILIES = setOf("wingdings", "wingdings 2", "wingdings 3", "webdings", "symbol")

private val WHITESPACE = Regex("\\s+")

private fun familyOf(font: String?): String? {
    val name = font?.trim()?.lowercase()?.replace(WHITESPACE, " ") ?: return null
    return name.takeIf { it in FAMILIES }
}

private fun codePointToString(codePoint: Int): String = String(Character.toChars(codePoint))

private val UNICODE_GLYPHS: Map<String, Map<Int, Int>> = mapOf(
    "wingdings" to mapOf(
        0x6C to 0x26AB,
        0x6D to 0x2BC3,
        0x6E to 0x25FC,
        0x71 to 0x2751,
        0x72 to 0x2752,
        0x74 to 0x29EB,
        0x75 to 0x25C6,
        0x76 to 0x2756,
        0x9F to 0x2022,
        0xA0 to 0x2B1D,
        0xA1 to 0x2B58,
        0xA5 to 0x2BBB,
        0xA7 to 0x25AA,
        0xAB to 0x2605,
        0xB4 to 0x2BCB,
        0xB5 to 0x272A,
        0xD8 to 0x2BA4,
        0xD9 to 0x2BA5,
        0xDA to 0x2BA6,
        0xDB to 0x2BAB,
        0xDC to 0x2BAC,
        0xDD to 0x2BAD,
        0xDE to 0x2BAE,
        0xDF to 0x1F858,
        0xE0 to 0x1F859,
    ),
    "wingdings 2" to mapOf(
        0x93 to 0x1F668,
        0x94 to 0x1F669,
        0x95 to 0x22C5,
        0x98 to 0x25CF,
        0x9C to 0x1F791,
        0x9E to 0x29BF,
        0x9F to 0x1F795,
        0xA0 to 0x1F797,
        0xA1 to 0x25FE,
        0xA2 to 0x25A0,
        0xAA to 0x2BBC,
        0xAB to 0x1F7A6,
        0xAD to 0x2B29,
        0xAE to 0x2B25,
        0xAF to 0x25C7,
        0xB6 to 0x2B2A,
        0xB7 to 0x2B27,
        0xB8 to 0x25CA,
        0xB9 to 0x1F7B7,
        0xBA to 0x25D6,
        0xBB to 0x25D7,
        0xBC to 0x2BB9,
        0xBD to 0x2BBA,
        0xBE to 0x2BB0,
        0xE4 to 0x1F7D7,
        0xE5 to 0x1F7D8,
        0xE6 to 0x1F7DA,
        0xE7 to 0x1F7DC,
        0xE8 to 0x1F7DE,
        0xE9 to 0x1F7E1,
        0xEA to 0x1F7E3,
        0xEB to 0x2736,
        0xEC to 0x1F7E7,
        0xED to 0x1F7E9,
        0xEE to 0x1F7EB,
        0xEF to 0x1F7ED,
        0xF0 to 0x2739,
        0xF1 to 0x1F7DB,
        0xF2 to 0x1F7DF,
        0xF3 to 0x272F,
        0xF4 to 0x1F7E8,
        0xF7 to 0x2BC8,
    ),
    "wingdings 3" to mapOf(
        0x21 to 0x2B68,
        0x22 to 0x2B69,
        0x23 to 0x2B6A,
        0x24 to 0x2B6B,
        0x25 to 0x2B6E,
        0x26 to 0x2B6F,
        0x27 to 0x2B70,
        0x28 to 0x2B71,
        0x29 to 0x2B86,
        0x2A to 0x2B87,
        0x2B to 0x2B88,
        0x2C to 0x2B89,
        0x2D to 0x2B8A,
        0x2E to 0x2B8B,
        0x31 to 0x2B6C,
        0x32 to 0x2B6D,
        0x33 to 0x2B72,
        0x34 to 0x2B74,
        0x35 to 0x2B73,
        0x36 to 0x2B75,
        0x37 to 0x2B96,
        0x6F to 0x25B2,
        0x70 to 0x25BC,
        0x71 to 0x25B3,
        0x72 to 0x25BD,
        0x73 to 0x25C0,
        0x74 to 0x25B6,
        0x75 to 0x25B7,
        0x78 to 0x25E3,
        0x79 to 0x25E2,
        0x7A to 0x25E4,
        0x7B to 0x25E5,
        0x84 to 0x2BB5,
        0x85 to 0x2BB6,
        0x86 to 0x2BB7,
        0x87 to 0x2BB8,
    ),
    "webdings" to mapOf(
        0x67 to 0x2B1B,
        0x6E to 0x2B24,
    ),
    "symbol" to mapOf(
        0xB7 to 0x2022,
        0xD7 to 0x22C5,
        0xE0 to 0x25CA,
    ),
)

private val VECTOR_GLYPHS: Map<String, Map<Int, SymbolShape>> = mapOf(
    "wingdings" to mapOf(
        0x6D to shadowed(ring(0.36, 0.26)),
        0x6F to box(0.30, 0.0),
        0x70 to box(0.34, 0.0),
        0x73 to diamondRing(0.36, 0.22),
        0x77 to diamondRing(0.28, 0.16),
        0xA2 to ring(0.34, 0.18),
        0xA3 to ring(0.40, 0.14),
        0xA4 to target(0.40, 0.16, 0.09),
        0xA6 to shadowed(ring(0.36, 0.26), dx = 0.06, dy = -0.06),
        0xA8 to box(0.24, 0.0),
        0xA9 to star(3, 0.40, 0.16, 0.0),
        0xAA to star(4, 0.42, 0.14, 0.0),
        0xAC to star(6, 0.40, 0.16, 0.0),
        0xAD to star(8, 0.42, 0.16, 0.0),
        0xAE to star(12, 0.44, 0.20, 0.0),
        0xAF to star(8, 0.42, 0.08, 0.0),
        0xB0 to indicator(square = true),
        0xB1 to indicator(square = false),
        0xB2 to star(4, 0.44, 0.10, 0.0),
        0xB3 to star(4, 0.44, 0.10, PI / 4),
    ),
    "wingdings 2" to mapOf(
        0xA6 to box(0.32, 0.06),
        0xA7 to box(0.32, 0.10),
        0xA8 to box(0.32, 0.14),
        0xA9 to target(0.40, 0.24, 0.14),
        0xB0 to diamondRing(0.34, 0.20),
        0xB1 to diamondRing(0.34, 0.24),
        0xB2 to diamondRing(0.44, 0.26),
        0xB3 to diamondRing(0.20, 0.10),
        0xB4 to diamondRing(0.28, 0.14),
    ),
)

private fun shadowed(shape: SymbolShape, dx: Double = 0.06, dy: Double = 0.06): SymbolShape = SymbolShape(
    shape.paths.map { path -> SymbolPath(path.segments.map { it.translated(dx, dy) }, opacity = 0.35) } +
        shape.paths,
)

private fun ring(outer: Double, inner: Double): SymbolShape = SymbolShape(
    listOf(SymbolPath(circleSegments(0.5, 0.5, outer) + circleSegments(0.5, 0.5, inner), evenOdd = true)),
)

private fun target(outer: Double, inner: Double, dot: Double): SymbolShape = SymbolShape(
    listOf(
        SymbolPath(circleSegments(0.5, 0.5, outer) + circleSegments(0.5, 0.5, inner), evenOdd = true),
        SymbolPath(circleSegments(0.5, 0.5, dot)),
    ),
)

private fun box(half: Double, innerHalf: Double): SymbolShape = SymbolShape(
    listOf(
        SymbolPath(
            rectSegments(0.5 - half, 0.5 - half, 0.5 + half, 0.5 + half) +
                rectSegments(0.5 - innerHalf, 0.5 - innerHalf, 0.5 + innerHalf, 0.5 + innerHalf),
            evenOdd = true,
        ),
    ),
)

private fun diamondRing(outer: Double, inner: Double): SymbolShape = SymbolShape(
    listOf(
        SymbolPath(
            diamondSegments(0.5, 0.5, outer) + diamondSegments(0.5, 0.5, inner),
            evenOdd = true,
        ),
    ),
)

private fun indicator(square: Boolean): SymbolShape {
    val frame = if (square) rectSegments(0.12, 0.12, 0.88, 0.88) else circleSegments(0.5, 0.5, 0.38)
    val bar = 0.05
    val cross = rectSegments(0.5 - bar, 0.2, 0.5 + bar, 0.8) + rectSegments(0.2, 0.5 - bar, 0.8, 0.5 + bar)
    return SymbolShape(listOf(SymbolPath(frame, evenOdd = true), SymbolPath(cross)))
}

private fun star(points: Int, outer: Double, inner: Double, rotation: Double): SymbolShape {
    val segments = ArrayList<SymbolSegment>(points * 2 + 1)
    repeat(points * 2) { index ->
        val radius = if (index % 2 == 0) outer else inner
        val angle = rotation - PI / 2 + index * PI / points
        val x = 0.5 + radius * cos(angle)
        val y = 0.5 + radius * sin(angle)
        segments += if (index == 0) SymbolSegment.MoveTo(x, y) else SymbolSegment.LineTo(x, y)
    }
    segments += SymbolSegment.Close
    return SymbolShape(listOf(SymbolPath(segments)))
}

private fun circleSegments(cx: Double, cy: Double, radius: Double): List<SymbolSegment> {
    val k = radius * KAPPA
    return listOf(
        SymbolSegment.MoveTo(cx + radius, cy),
        SymbolSegment.CubicTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius),
        SymbolSegment.CubicTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy),
        SymbolSegment.CubicTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius),
        SymbolSegment.CubicTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy),
        SymbolSegment.Close,
    )
}

private fun rectSegments(left: Double, top: Double, right: Double, bottom: Double): List<SymbolSegment> = listOf(
    SymbolSegment.MoveTo(left, top),
    SymbolSegment.LineTo(right, top),
    SymbolSegment.LineTo(right, bottom),
    SymbolSegment.LineTo(left, bottom),
    SymbolSegment.Close,
)

private fun diamondSegments(cx: Double, cy: Double, radius: Double): List<SymbolSegment> = listOf(
    SymbolSegment.MoveTo(cx, cy - radius),
    SymbolSegment.LineTo(cx + radius, cy),
    SymbolSegment.LineTo(cx, cy + radius),
    SymbolSegment.LineTo(cx - radius, cy),
    SymbolSegment.Close,
)

private fun SymbolSegment.translated(dx: Double, dy: Double): SymbolSegment = when (this) {
    is SymbolSegment.MoveTo -> copy(x = x + dx, y = y + dy)
    is SymbolSegment.LineTo -> copy(x = x + dx, y = y + dy)
    is SymbolSegment.CubicTo -> copy(
        control1X = control1X + dx,
        control1Y = control1Y + dy,
        control2X = control2X + dx,
        control2Y = control2Y + dy,
        x = x + dx,
        y = y + dy,
    )
    SymbolSegment.Close -> this
}

private const val KAPPA = 0.5522847498307936
