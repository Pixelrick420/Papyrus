package io.github.pixelrick420.papyrus.data

import kotlin.math.abs
import kotlin.math.roundToInt

// Each kind anchors to something independent of viewport width, zoom and text size, so a position survives rotation, pinch and re-wrap.
enum class LocationKind(val code: Int) {
    PAGE(1),

    CHUNK(2),

    BLOCK(3),

    CHAR(4);

    companion object {
        /** `null` for a code this build does not know, so an unknown stored value is ignored, never a crash. */
        fun fromCode(code: Int): LocationKind? = entries.firstOrNull { it.code == code }

        fun forFormat(format: DocumentFormat): LocationKind? = when (format) {
            DocumentFormat.PDF -> PAGE
            DocumentFormat.MARKDOWN -> CHAR
            DocumentFormat.TEXT, DocumentFormat.CODE -> CHUNK
            DocumentFormat.DOC, DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.EPUB, DocumentFormat.CSV -> BLOCK
            DocumentFormat.UNKNOWN -> null
        }
    }
}

data class ReadingLocation(
    val kind: LocationKind,
    val unit: Int,
    val fraction: Float = 0f,
) {

    // Files change between opens and an encoding override shifts chunk geometry, so a stored value is repaired, never trusted.
    fun clampedTo(unitCount: Int): ReadingLocation {
        val last = (unitCount - 1).coerceAtLeast(0)
        val inRange = unit.coerceIn(0, last)
        val within = if (kind == LocationKind.CHAR || inRange != unit) 0f else sanitizeFraction(fraction)
        return ReadingLocation(kind, inRange, within)
    }

    // [atEnd] reads as done: the last page top is otherwise stuck short of 100%.
    fun progress(unitCount: Int, atEnd: Boolean = false): Float {
        if (atEnd) return 1f
        if (unitCount <= 0) return 0f
        val at = clampedTo(unitCount)
        val done = if (kind == LocationKind.CHAR) at.unit.toFloat() else at.unit + at.fraction
        return (done / unitCount).coerceIn(0f, 1f)
    }

    // The tolerance absorbs rounding between a pixel offset and a fraction, so not scrolling is never mistaken for a scroll.
    fun isSameSpot(other: ReadingLocation): Boolean =
        kind == other.kind && unit == other.unit && abs(fraction - other.fraction) <= SAME_SPOT_TOLERANCE

    private companion object {
        const val SAME_SPOT_TOLERANCE = 0.01f
    }
}

private fun sanitizeFraction(fraction: Float): Float = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)

fun fractionWithin(offsetPx: Int, sizePx: Int): Float =
    if (sizePx <= 0) 0f else (offsetPx.toFloat() / sizePx).coerceIn(0f, 1f)

// Kept strictly inside the item: a full fraction would otherwise be the next item's top.
fun offsetWithin(fraction: Float, sizePx: Int): Int =
    if (sizePx <= 0) 0 else (sanitizeFraction(fraction) * sizePx).roundToInt().coerceIn(0, sizePx - 1)

// [zoom] is the committed scale, so a pinch alone counts as having moved.
data class PositionSample(
    val location: ReadingLocation,
    val unitCount: Int,
    val atEnd: Boolean,
    val zoom: Float,
) {
    val progress: Float get() = location.progress(unitCount, atEnd)

    fun isSameSpot(other: PositionSample): Boolean =
        location.isSameSpot(other.location) && atEnd == other.atEnd && abs(zoom - other.zoom) <= ZOOM_TOLERANCE

    private companion object {
        const val ZOOM_TOLERANCE = 0.01f
    }
}

data class RestoreRequest(val location: ReadingLocation, val zoom: Float)

fun ReadingProgressEntity.toRestoreRequest(format: DocumentFormat): RestoreRequest? {
    val kind = LocationKind.fromCode(locationKind) ?: return null
    // A row written for another kind of content (the format of the row changed) means nothing here.
    if (kind != LocationKind.forFormat(format)) return null
    return RestoreRequest(ReadingLocation(kind, locationUnit, locationFraction), zoomScale)
}
