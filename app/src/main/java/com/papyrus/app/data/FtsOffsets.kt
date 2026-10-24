package com.papyrus.app.data

/**
 * Where the matched words are in a document, read from FTS4's `offsets()` output.
 *
 * `offsets()` returns space-separated integers, four per matched term instance: the column number,
 * the term's number within the query, the **byte** offset of the term in that column and its length
 * in bytes. Only the earliest offset is kept: it is where the excerpt is centred, and therefore the
 * hit a reader means when they tap a result.
 *
 * The offset is in bytes, not characters, because bytes are what SQLite counts. Converting it to a
 * character index means walking the same UTF-8 text the extractor re-derives, which is why that
 * text's shape is a documented contract rather than an implementation detail.
 */
object FtsOffsets {

    /** The four values that describe one term instance. */
    private const val VALUES_PER_MATCH = 4

    /** Index of the byte offset within one match's four values. */
    private const val OFFSET_INDEX = 2

    /** The earliest matched byte offset in the document's text, or null when nothing matched. */
    fun earliest(offsets: String?): Int? {
        if (offsets.isNullOrBlank()) return null
        val numbers = offsets.trim().split(' ').mapNotNull(String::toIntOrNull)
        var earliest: Int? = null
        for (start in numbers.indices step VALUES_PER_MATCH) {
            val offset = numbers.getOrNull(start + OFFSET_INDEX) ?: break
            if (offset < (earliest ?: Int.MAX_VALUE)) earliest = offset
        }
        return earliest
    }
}
