package io.github.pixelrick420.papyrus.viewer

// [depth] is relative: 0 is the shallowest entry the document has, whatever its style was called. [NO_OUTLINE_TARGET] marks an entry that is listed but cannot be chosen (a PDF bookmark that only groups others).
data class OutlineEntry(val index: Int, val depth: Int, val title: String)

const val NO_OUTLINE_TARGET = -1

private val WHITESPACE_RUN = Regex("\\s+")

/** A heading is a line in a list; past this a long one is cut, not wrapped over the whole sheet. */
private const val MAX_TITLE_CHARS = 200

/** Deeper than this reads as a staircase on a phone, so further levels share the last indent. */
private const val MAX_OUTLINE_DEPTH = 5

fun cleanOutlineTitle(raw: String): String = raw.replace(WHITESPACE_RUN, " ").trim().take(MAX_TITLE_CHARS)

fun officeOutline(blocks: List<OfficeBlock>): List<OutlineEntry> {
    // `depth` holds the heading's own level here; it is made relative below, once the shallowest is known.
    val found = blocks.mapIndexedNotNull { index, block ->
        val heading = block as? OfficeBlock.Heading ?: return@mapIndexedNotNull null
        val title = cleanOutlineTitle(heading.text)
        if (title.isEmpty()) null else OutlineEntry(index, heading.level, title)
    }
    return withRelativeDepth(found)
}

// Titles are cleaned like a heading's, an entry with no words is left out, and depth is made relative, so the same sheet shows both kinds.
fun explicitOutline(entries: List<OutlineEntry>): List<OutlineEntry> = withRelativeDepth(
    entries.mapNotNull { entry ->
        val title = cleanOutlineTitle(entry.title)
        if (title.isEmpty()) null else entry.copy(title = title)
    },
)

/** A document that opens at Heading 2 is not indented for a level above that it never uses. */
private fun withRelativeDepth(found: List<OutlineEntry>): List<OutlineEntry> {
    val shallowest = found.minOfOrNull { it.depth } ?: return found
    return found.map { it.copy(depth = (it.depth - shallowest).coerceIn(0, MAX_OUTLINE_DEPTH)) }
}
