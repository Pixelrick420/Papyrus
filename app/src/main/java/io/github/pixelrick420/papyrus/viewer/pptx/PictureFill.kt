package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.max

/** A picture's `a:srcRect` crop, as fractions of the source. A negative side pads that side with transparency. */
internal data class PictureCrop(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
) {

    val width: Double get() = 1.0 - left - right

    val height: Double get() = 1.0 - top - bottom

    val isVisible: Boolean get() = width > 0.0 && height > 0.0

    companion object {
        val NONE = PictureCrop()
    }
}

/** An `a:fillRect` inset, as fractions of the picture frame. */
internal data class PictureInsets(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
) {

    companion object {
        val NONE = PictureInsets()
    }
}

internal enum class PictureTileAlignment {
    TOP_LEFT,
    TOP,
    TOP_RIGHT,
    LEFT,
    CENTRE,
    RIGHT,
    BOTTOM_LEFT,
    BOTTOM,
    BOTTOM_RIGHT,
    ;

    companion object {
        fun of(value: String?): PictureTileAlignment = when (value) {
            "t" -> TOP
            "tr" -> TOP_RIGHT
            "l" -> LEFT
            "ctr" -> CENTRE
            "r" -> RIGHT
            "bl" -> BOTTOM_LEFT
            "b" -> BOTTOM
            "br" -> BOTTOM_RIGHT
            else -> TOP_LEFT
        }
    }
}

internal enum class PictureTileFlip {
    NONE,
    HORIZONTAL,
    VERTICAL,
    BOTH,
    ;

    val horizontal: Boolean get() = this == HORIZONTAL || this == BOTH

    val vertical: Boolean get() = this == VERTICAL || this == BOTH

    companion object {
        fun of(value: String?): PictureTileFlip = when (value) {
            "x" -> HORIZONTAL
            "y" -> VERTICAL
            "xy" -> BOTH
            else -> NONE
        }
    }
}

internal class PictureTile(
    val offsetXEmu: Double = 0.0,
    val offsetYEmu: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val alignment: PictureTileAlignment = PictureTileAlignment.TOP_LEFT,
    val flip: PictureTileFlip = PictureTileFlip.NONE,
)

internal sealed interface PictureFill {

    class Stretch(val insets: PictureInsets = PictureInsets.NONE) : PictureFill

    class Tile(val tile: PictureTile) : PictureFill
}

/** A `p:blipFill` as the paint pass consumes it: its image, alpha, crop and how it covers the frame. */
internal class PictureSource(
    val relationshipId: String? = null,
    val alpha: Double = 1.0,
    val crop: PictureCrop = PictureCrop.NONE,
    val fill: PictureFill = PictureFill.Stretch(),
)

internal object PictureFills {

    private const val CROP_LIMIT = 1.0
    private const val TILE_SCALE_MIN = 0.001
    private const val TILE_SCALE_MAX = 100.0

    fun read(blipFill: XNode?): PictureSource? {
        if (blipFill == null) return null
        val blip = blipFill.child(OoxmlNamespace.A, "blip")
        return PictureSource(
            relationshipId = blip?.attribute(OoxmlNamespace.R, "embed"),
            alpha = alphaOf(blip),
            crop = cropOf(blipFill.child(OoxmlNamespace.A, "srcRect")),
            fill = fillOf(blipFill),
        )
    }

    private fun fillOf(blipFill: XNode): PictureFill {
        blipFill.child(OoxmlNamespace.A, "tile")?.let { return PictureFill.Tile(tileOf(it)) }
        val fillRect = blipFill.child(OoxmlNamespace.A, "stretch")?.child(OoxmlNamespace.A, "fillRect")
        return PictureFill.Stretch(insetsOf(fillRect))
    }

    private fun tileOf(node: XNode): PictureTile = PictureTile(
        offsetXEmu = coordinate(node.attribute("tx")),
        offsetYEmu = coordinate(node.attribute("ty")),
        scaleX = scale(node.attribute("sx")),
        scaleY = scale(node.attribute("sy")),
        alignment = PictureTileAlignment.of(node.attribute("algn")),
        flip = PictureTileFlip.of(node.attribute("flip")),
    )

    private fun cropOf(node: XNode?): PictureCrop {
        if (node == null) return PictureCrop.NONE
        return PictureCrop(
            left = fraction(node.attribute("l")),
            top = fraction(node.attribute("t")),
            right = fraction(node.attribute("r")),
            bottom = fraction(node.attribute("b")),
        )
    }

    private fun insetsOf(node: XNode?): PictureInsets {
        if (node == null) return PictureInsets.NONE
        return PictureInsets(
            left = fraction(node.attribute("l")),
            top = fraction(node.attribute("t")),
            right = fraction(node.attribute("r")),
            bottom = fraction(node.attribute("b")),
        )
    }

    /** The product of every alpha effect on the blip, so a file that states more than one gets all of them. */
    private fun alphaOf(blip: XNode?): Double =
        blip?.children.orEmpty()
            .filter { it.name.namespace == OoxmlNamespace.A && it.name.localName in ALPHA_TAGS }
            .fold(1.0) { alpha, node -> alpha * fraction(node.attribute("amt"), default = 1.0) }
            .coerceIn(0.0, 1.0)

    private fun fraction(value: String?, default: Double = 0.0): Double {
        val number = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return default
        return (number / PERCENT_UNIT).coerceIn(-CROP_LIMIT, CROP_LIMIT)
    }

    private fun scale(value: String?): Double {
        val number = value?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return 1.0
        return (number / PERCENT_UNIT).coerceIn(TILE_SCALE_MIN, TILE_SCALE_MAX)
    }

    private fun coordinate(value: String?): Double {
        val number = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0.0
        return number.coerceIn(-Affine.MAX_EDGE_EMU, Affine.MAX_EDGE_EMU)
    }

    private val ALPHA_TAGS = setOf("alphaModFix", "alphaMod")

    private const val PERCENT_UNIT = 100_000.0
}

/** The visible part of a picture's frame after a stretch, which an `a:fillRect` can inset. */
internal fun stretchRect(frame: SceneRect, insets: PictureInsets): SceneRect {
    val left = frame.width * insets.left
    val top = frame.height * insets.top
    val right = frame.width * insets.right
    val bottom = frame.height * insets.bottom
    return SceneRect(
        x = frame.x + left,
        y = frame.y + top,
        width = max(0.0, frame.width - left - right),
        height = max(0.0, frame.height - top - bottom),
    )
}

/**
 * The rectangle the whole source bitmap would occupy for [crop] to be visible in [destination]. A negative
 * crop side pushes that rectangle inward, leaving the extra source area transparent.
 */
internal fun contentRect(destination: SceneRect, crop: PictureCrop): SceneRect {
    if (!crop.isVisible) return SceneRect(destination.x, destination.y, 0.0, 0.0)
    val width = destination.width / crop.width
    val height = destination.height / crop.height
    return SceneRect(
        x = destination.x - crop.left * width,
        y = destination.y - crop.top * height,
        width = width,
        height = height,
    )
}

/** A tile's size in EMU: the image's 96-DPI size scaled by the tile's own percentages. */
internal fun tileSize(bounds: ImageBounds, tile: PictureTile): SceneSize = SceneSize(
    width = bounds.width.toDouble() * EMU_PER_PIXEL * tile.scaleX,
    height = bounds.height.toDouble() * EMU_PER_PIXEL * tile.scaleY,
)

/** Where the first tile starts inside [frame], before its own `tx`/`ty` offsets. */
internal fun tileOrigin(frame: SceneRect, size: SceneSize, alignment: PictureTileAlignment): ScenePoint {
    val x = when (alignment) {
        PictureTileAlignment.TOP_LEFT,
        PictureTileAlignment.LEFT,
        PictureTileAlignment.BOTTOM_LEFT,
        -> 0.0
        PictureTileAlignment.TOP,
        PictureTileAlignment.CENTRE,
        PictureTileAlignment.BOTTOM,
        -> (frame.width - size.width) / 2.0
        else -> frame.width - size.width
    }
    val y = when (alignment) {
        PictureTileAlignment.TOP_LEFT,
        PictureTileAlignment.TOP,
        PictureTileAlignment.TOP_RIGHT,
        -> 0.0
        PictureTileAlignment.LEFT,
        PictureTileAlignment.CENTRE,
        PictureTileAlignment.RIGHT,
        -> (frame.height - size.height) / 2.0
        else -> frame.height - size.height
    }
    return ScenePoint(x, y)
}

/** The first tile start at or before zero, so a negative offset still covers the frame. */
internal fun firstTile(origin: Double, size: Double): Double {
    if (size <= 0.0) return origin
    return origin - kotlin.math.ceil(origin / size) * size
}

internal fun visibleTiles(start: Double, size: Double, extent: Double): Int {
    if (size <= 0.0 || extent <= 0.0 || start > extent) return 0
    return kotlin.math.ceil((extent - start) / size).toInt()
}

internal const val EMU_PER_PIXEL = 9_525.0
