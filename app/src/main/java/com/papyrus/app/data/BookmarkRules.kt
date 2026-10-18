package com.papyrus.app.data

import com.papyrus.app.viewer.TEXT_LINES_PER_CHUNK
import kotlin.math.abs
import kotlin.math.roundToInt

/** One document holds at most this many bookmarks: a list a reader can still scan, and a bound on what one file can store. */
const val MAX_BOOKMARKS_PER_DOCUMENT = 200

/** Longest label kept; a longer one is cut, since the row shows it on one line. */
const val MAX_BOOKMARK_LABEL_CHARS = 80

/** What adding a bookmark at some place does, decided before anything is written. */
enum class BookmarkDecision { ADD, ALREADY_BOOKMARKED, LIMIT_REACHED }

/** The outcome of an add as the caller sees it. */
sealed interface BookmarkAdd {
    /** The bookmark now stored, with the id it got. */
    data class Added(val bookmark: BookmarkEntity) : BookmarkAdd

    data object AlreadyBookmarked : BookmarkAdd

    data object LimitReached : BookmarkAdd

    /** A transient document has no row for a bookmark to hang from. */
    data object NotInLibrary : BookmarkAdd
}

/**
 * How near two places of one kind must be to count as the same bookmark. A page is the unit a reader
 * thinks in, so any two places on it are one; the other kinds allow a couple of lines. Two kinds never
 * match: a row written for another kind of content says nothing about this one.
 */
fun ReadingLocation.isSameBookmarkSpot(other: ReadingLocation): Boolean {
    if (kind != other.kind) return false
    return when (kind) {
        LocationKind.PAGE -> unit == other.unit
        LocationKind.CHUNK -> unit == other.unit && abs(fraction - other.fraction) <= CHUNK_TOLERANCE
        LocationKind.BLOCK -> unit == other.unit && abs(fraction - other.fraction) <= BLOCK_TOLERANCE
        LocationKind.CHAR -> abs(unit.toLong() - other.unit.toLong()) <= CHAR_TOLERANCE
    }
}

/** Two lines of a 40-line chunk. */
private const val CHUNK_TOLERANCE = 2f / TEXT_LINES_PER_CHUNK

/** A tenth of a block: a long table or a page-sized paragraph can hold two bookmarks, a sentence cannot. */
private const val BLOCK_TOLERANCE = 0.1f

/** A couple of rows of Markdown text: the offset is that of the first character of the top line. */
private const val CHAR_TOLERANCE = 100L

/**
 * Whether a bookmark at [candidate] may be added to a document that already has one at each of
 * [existing]. A spot that is bookmarked already is reported as such even when the list is full, because
 * "already bookmarked" is the more useful answer than "full".
 */
fun decideBookmark(
    existing: List<ReadingLocation>,
    candidate: ReadingLocation,
    limit: Int = MAX_BOOKMARKS_PER_DOCUMENT,
): BookmarkDecision = when {
    existing.any { it.isSameBookmarkSpot(candidate) } -> BookmarkDecision.ALREADY_BOOKMARKED
    existing.size >= limit -> BookmarkDecision.LIMIT_REACHED
    else -> BookmarkDecision.ADD
}

/** What a location reads as to a person: a page, a line, or how far through the document it is. */
enum class SummaryKind { PAGE, LINE, PERCENT }

data class LocationSummary(val kind: SummaryKind, val number: Int)

/**
 * "Page 12", "Line 340" or "45%" for this place in a document of [unitCount] units, as numbers the
 * screen turns into words. The place is clamped first, so a bookmark whose page or chunk no longer
 * exists reads as the nearest real one rather than as a number past the end. Only positions, never text.
 */
fun ReadingLocation.summary(unitCount: Int): LocationSummary {
    val at = clampedTo(unitCount)
    return when (kind) {
        LocationKind.PAGE -> LocationSummary(SummaryKind.PAGE, at.unit + 1)
        LocationKind.CHUNK -> {
            val inChunk = (at.fraction * TEXT_LINES_PER_CHUNK).toInt().coerceIn(0, TEXT_LINES_PER_CHUNK - 1)
            LocationSummary(SummaryKind.LINE, at.unit * TEXT_LINES_PER_CHUNK + inChunk + 1)
        }
        LocationKind.BLOCK, LocationKind.CHAR ->
            LocationSummary(SummaryKind.PERCENT, (progress(unitCount) * PERCENT).roundToInt().coerceIn(0, PERCENT))
    }
}

private const val PERCENT = 100

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * A label the reader typed, as one line of at most [MAX_BOOKMARK_LABEL_CHARS]; [fallback] (the positional
 * default) when nothing but whitespace was typed, so a bookmark is never unnamed.
 */
fun cleanBookmarkLabel(raw: String, fallback: String): String =
    raw.replace(WHITESPACE_RUN, " ").trim().take(MAX_BOOKMARK_LABEL_CHARS).trim().ifEmpty { fallback }

/** A bookmark as a viewer uses it: where it points, with the stored row's identity and name. */
data class Bookmark(val id: Long, val label: String, val location: ReadingLocation, val createdAt: Long)

/** The place this row stores; null for a kind code this build does not know, so it is ignored, never a crash. */
fun BookmarkEntity.toLocation(): ReadingLocation? =
    LocationKind.fromCode(locationKind)?.let { ReadingLocation(it, locationUnit, locationFraction) }

/**
 * The bookmarks of a document of [format] in document order: by place, then by when they were made. A
 * row of another kind than the format reads in (the format of the row changed) or of an unknown kind
 * is left out.
 */
fun List<BookmarkEntity>.forFormat(format: DocumentFormat): List<Bookmark> {
    val kind = LocationKind.forFormat(format) ?: return emptyList()
    return mapNotNull { row ->
        val location = row.toLocation()?.takeIf { it.kind == kind } ?: return@mapNotNull null
        Bookmark(row.id, row.label, location, row.createdAt)
    }.sortedWith(compareBy({ it.location.unit }, { it.location.fraction }, { it.createdAt }, { it.id }))
}
