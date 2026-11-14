package io.github.pixelrick420.papyrus.data

import io.github.pixelrick420.papyrus.viewer.TEXT_LINES_PER_CHUNK
import kotlin.math.abs
import kotlin.math.roundToInt

const val MAX_BOOKMARKS_PER_DOCUMENT = 200

const val MAX_BOOKMARK_LABEL_CHARS = 80

enum class BookmarkDecision { ADD, ALREADY_BOOKMARKED, LIMIT_REACHED }

sealed interface BookmarkAdd {
    data class Added(val bookmark: BookmarkEntity) : BookmarkAdd

    data object AlreadyBookmarked : BookmarkAdd

    data object LimitReached : BookmarkAdd

    /** A transient document has no row for a bookmark to hang from. */
    data object NotInLibrary : BookmarkAdd
}

// A page is one unit, so any two places on it match; other kinds allow a couple of lines. Different kinds never match.
fun ReadingLocation.isSameBookmarkSpot(other: ReadingLocation): Boolean {
    if (kind != other.kind) return false
    return when (kind) {
        LocationKind.PAGE -> unit == other.unit
        LocationKind.CHUNK -> unit == other.unit && abs(fraction - other.fraction) <= CHUNK_TOLERANCE
        LocationKind.BLOCK -> unit == other.unit && abs(fraction - other.fraction) <= BLOCK_TOLERANCE
        LocationKind.CHAR -> abs(unit.toLong() - other.unit.toLong()) <= CHAR_TOLERANCE
    }
}

private const val CHUNK_TOLERANCE = 2f / TEXT_LINES_PER_CHUNK

/** A tenth of a block: a long table or a page-sized paragraph can hold two bookmarks, a sentence cannot. */
private const val BLOCK_TOLERANCE = 0.1f

/** A couple of rows of Markdown text: the offset is that of the first character of the top line. */
private const val CHAR_TOLERANCE = 100L

// An already-bookmarked spot is reported as such even when the list is full: more useful than "full".
fun decideBookmark(
    existing: List<ReadingLocation>,
    candidate: ReadingLocation,
    limit: Int = MAX_BOOKMARKS_PER_DOCUMENT,
): BookmarkDecision = when {
    existing.any { it.isSameBookmarkSpot(candidate) } -> BookmarkDecision.ALREADY_BOOKMARKED
    existing.size >= limit -> BookmarkDecision.LIMIT_REACHED
    else -> BookmarkDecision.ADD
}

enum class SummaryKind { PAGE, LINE, PERCENT }

data class LocationSummary(val kind: SummaryKind, val number: Int)

// Position only, never text. Clamped, so a bookmark past the end reads as the nearest real place.
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

// Falls back to [fallback] when only whitespace was typed, so a bookmark is never unnamed.
fun cleanBookmarkLabel(raw: String, fallback: String): String =
    raw.replace(WHITESPACE_RUN, " ").trim().take(MAX_BOOKMARK_LABEL_CHARS).trim().ifEmpty { fallback }

data class Bookmark(val id: Long, val label: String, val location: ReadingLocation, val createdAt: Long)

/** The place this row stores; null for a kind code this build does not know, so it is ignored, never a crash. */
fun BookmarkEntity.toLocation(): ReadingLocation? =
    LocationKind.fromCode(locationKind)?.let { ReadingLocation(it, locationUnit, locationFraction) }

// Rows of another kind than the format's, or of an unknown kind, are left out.
fun List<BookmarkEntity>.forFormat(format: DocumentFormat): List<Bookmark> {
    val kind = LocationKind.forFormat(format) ?: return emptyList()
    return mapNotNull { row ->
        val location = row.toLocation()?.takeIf { it.kind == kind } ?: return@mapNotNull null
        Bookmark(row.id, row.label, location, row.createdAt)
    }.sortedWith(compareBy({ it.location.unit }, { it.location.fraction }, { it.createdAt }, { it.id }))
}
