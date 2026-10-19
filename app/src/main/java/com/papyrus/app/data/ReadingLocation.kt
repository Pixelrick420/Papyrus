package com.papyrus.app.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a [ReadingLocation.unit] counts. Each kind anchors to something independent of viewport width,
 * zoom and text size, so a position survives rotation, pinch, encoding change and re-wrap.
 */
enum class LocationKind(val code: Int) {
    /** [ReadingLocation.unit] is a page index; the fraction is how far down that page the viewport top is. */
    PAGE(1),

    /** A 40-line chunk of a plain-text or code file, plus a fraction within the chunk. */
    CHUNK(2),

    /** A block of an Office-style document (DOCX, ODT, DOC, later EPUB), plus a fraction within it. */
    BLOCK(3),

    /** A character offset into the rendered Markdown text. It has no fraction: the offset is already fine. */
    CHAR(4);

    companion object {
        /** `null` for a code this build does not know, so an unknown stored value is ignored, never a crash. */
        fun fromCode(code: Int): LocationKind? = entries.firstOrNull { it.code == code }

        /** The kind a format is read in, `null` for [DocumentFormat.UNKNOWN], which has no viewer. */
        fun forFormat(format: DocumentFormat): LocationKind? = when (format) {
            DocumentFormat.PDF -> PAGE
            DocumentFormat.MARKDOWN -> CHAR
            DocumentFormat.TEXT, DocumentFormat.CODE -> CHUNK
            DocumentFormat.DOC, DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.EPUB -> BLOCK
            DocumentFormat.UNKNOWN -> null
        }
    }
}

/**
 * A place in a document: the top of the viewport, as a [kind]-specific [unit] plus a [fraction] of that
 * unit (0 when the kind has none). Shared by reading position, bookmarks and the viewer's jump API.
 */
data class ReadingLocation(
    val kind: LocationKind,
    val unit: Int,
    val fraction: Float = 0f,
) {

    /**
     * Moves this location onto a document with [unitCount] units. Files change between opens and an
     * encoding override shifts chunk geometry, so a stored value is repaired, never trusted.
     */
    fun clampedTo(unitCount: Int): ReadingLocation {
        val last = (unitCount - 1).coerceAtLeast(0)
        val inRange = unit.coerceIn(0, last)
        val within = if (kind == LocationKind.CHAR || inRange != unit) 0f else sanitizeFraction(fraction)
        return ReadingLocation(kind, inRange, within)
    }

    /**
     * How much of a [unitCount]-unit document lies above this location, 0..1. [atEnd] means the viewport
     * cannot scroll further, which reads as done: the last page top is otherwise stuck short of 100%.
     */
    fun progress(unitCount: Int, atEnd: Boolean = false): Float {
        if (atEnd) return 1f
        if (unitCount <= 0) return 0f
        val at = clampedTo(unitCount)
        val done = if (kind == LocationKind.CHAR) at.unit.toFloat() else at.unit + at.fraction
        return (done / unitCount).coerceIn(0f, 1f)
    }

    /**
     * Whether [other] is the same spot: same unit, fraction within a hair. The tolerance absorbs rounding
     * between a pixel offset and a fraction, so "I did not scroll" is not mistaken for a scroll.
     */
    fun isSameSpot(other: ReadingLocation): Boolean =
        kind == other.kind && unit == other.unit && abs(fraction - other.fraction) <= SAME_SPOT_TOLERANCE

    private companion object {
        const val SAME_SPOT_TOLERANCE = 0.01f
    }
}

/** [fraction] as something a fraction can be: NaN becomes 0, the rest is held to 0..1. */
private fun sanitizeFraction(fraction: Float): Float = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)

/** How far [offsetPx] is into an item [sizePx] tall, 0..1; 0 for an item with no size yet. */
fun fractionWithin(offsetPx: Int, sizePx: Int): Float =
    if (sizePx <= 0) 0f else (offsetPx.toFloat() / sizePx).coerceIn(0f, 1f)

/**
 * The pixel offset into an item [sizePx] tall that [fraction] stands for, kept strictly inside the item:
 * a full fraction would otherwise be the next item's top and read back as the next unit.
 */
fun offsetWithin(fraction: Float, sizePx: Int): Int =
    if (sizePx <= 0) 0 else (sanitizeFraction(fraction) * sizePx).roundToInt().coerceIn(0, sizePx - 1)

/**
 * One observation of the viewport, with everything a save needs. [zoom] is the committed scale, so a
 * pinch alone counts as having moved.
 */
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

/** Where a viewer should open: the (already trusted-or-clamped by the viewer) location and zoom. */
data class RestoreRequest(val location: ReadingLocation, val zoom: Float)

/** A stored position, as a restore request for a document of [format]; `null` if it cannot apply. */
fun ReadingProgressEntity.toRestoreRequest(format: DocumentFormat): RestoreRequest? {
    val kind = LocationKind.fromCode(locationKind) ?: return null
    // A row written for another kind of content (the format of the row changed) means nothing here.
    if (kind != LocationKind.forFormat(format)) return null
    return RestoreRequest(ReadingLocation(kind, locationUnit, locationFraction), zoomScale)
}
