package io.github.pixelrick420.papyrus.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.github.pixelrick420.papyrus.viewer.cleanOutlineTitle

// Used only while the file is the same size and [cacheVersion] matches this build; [entries] is [encodeOutline]'s packed text.
@Entity(
    tableName = "pdf_outlines",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PdfOutlineEntity(
    @PrimaryKey val documentId: Long,
    val sourceSize: Long,
    val cacheVersion: Int,
    val entries: String,
)

@Dao
interface PdfOutlineDao {

    @Query("SELECT * FROM pdf_outlines WHERE documentId = :documentId")
    suspend fun get(documentId: Long): PdfOutlineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: PdfOutlineEntity)
}

// Raise whenever the outline reader or this encoding changes shape, so old rows are ignored.
const val PDF_OUTLINE_CACHE_VERSION = 1

/** Deeper than the reader ever produces; a damaged row is clamped instead of indenting off the sheet. */
private const val MAX_CACHED_DEPTH = 32

// `index depth titleLength:title` per entry; the length is written down because a title may hold any character.
internal fun encodeOutline(entries: List<OutlineEntry>): String = buildString {
    for (entry in entries) {
        append(entry.index).append(' ')
        append(entry.depth).append(' ')
        append(entry.title.length).append(':')
        append(entry.title)
    }
}

// The entries [encodeOutline] wrote. Decoding stops at the first damaged entry and keeps the ones
// before it: a broken cache shows fewer bookmarks, it never fails the open.
internal fun decodeOutline(raw: String): List<OutlineEntry> {
    val out = ArrayList<OutlineEntry>()
    var at = 0
    while (at < raw.length) {
        val index = readNumber(raw, at, ' ') ?: break
        val depth = readNumber(raw, index.end, ' ') ?: break
        val length = readNumber(raw, depth.end, ':') ?: break
        val start = length.end
        val end = start + length.value
        if (length.value < 0 || end > raw.length) break
        val title = cleanOutlineTitle(raw.substring(start, end))
        if (title.isNotEmpty()) {
            out += OutlineEntry(index.value, depth.value.coerceIn(0, MAX_CACHED_DEPTH), title)
        }
        at = end
    }
    return out
}

private class ParsedNumber(val value: Int, val end: Int)

private fun readNumber(raw: String, at: Int, delimiter: Char): ParsedNumber? {
    if (at >= raw.length) return null
    val stop = raw.indexOf(delimiter, at)
    if (stop <= at) return null
    val value = raw.substring(at, stop).toIntOrNull() ?: return null
    return ParsedNumber(value, stop + 1)
}
