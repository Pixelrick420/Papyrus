package com.papyrus.app.viewer

/**
 * One heading: [index] is the block it came from, also its row in the viewer's list, and [depth] is
 * relative, 0 being the shallowest heading the document has whatever its style was called.
 */
data class OutlineEntry(val index: Int, val depth: Int, val title: String)

private val WHITESPACE_RUN = Regex("\\s+")

/** A heading is a line in a list; past this a long one is cut, not wrapped over the whole sheet. */
private const val MAX_TITLE_CHARS = 200

/** Deeper than this reads as a staircase on a phone, so further levels share the last indent. */
private const val MAX_OUTLINE_DEPTH = 5

/**
 * The headings of [blocks] in reading order, or empty when the author never used heading styles.
 * A heading with no visible words is left out, and one that wraps or breaks shows as a single line.
 */
fun officeOutline(blocks: List<OfficeBlock>): List<OutlineEntry> {
    // `depth` holds the heading's own level here; it is made relative below, once the shallowest is known.
    val found = blocks.mapIndexedNotNull { index, block ->
        val heading = block as? OfficeBlock.Heading ?: return@mapIndexedNotNull null
        val title = heading.text.replace(WHITESPACE_RUN, " ").trim().take(MAX_TITLE_CHARS)
        if (title.isEmpty()) null else OutlineEntry(index, heading.level, title)
    }
    // A document that opens at Heading 2 is not indented for a level above that it never uses.
    val shallowest = found.minOfOrNull { it.depth } ?: return found
    return found.map { it.copy(depth = (it.depth - shallowest).coerceIn(0, MAX_OUTLINE_DEPTH)) }
}
