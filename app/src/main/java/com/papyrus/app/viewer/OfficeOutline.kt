package com.papyrus.app.viewer

/**
 * One entry in a document's outline. [index] is where it jumps to: the block an Office heading came
 * from (also its row in the viewer's list), or the page a PDF bookmark points at; either way jumping
 * is a scroll to that row. [NO_OUTLINE_TARGET] marks an entry that goes nowhere, such as a PDF
 * bookmark that only groups others: it is listed, but cannot be chosen. [depth] is relative: 0 is
 * the shallowest entry the document has, whatever its style was called.
 */
data class OutlineEntry(val index: Int, val depth: Int, val title: String)

/** The [OutlineEntry.index] of an entry that has no destination of its own. */
const val NO_OUTLINE_TARGET = -1

private val WHITESPACE_RUN = Regex("\\s+")

/** A heading is a line in a list; past this a long one is cut, not wrapped over the whole sheet. */
private const val MAX_TITLE_CHARS = 200

/** Deeper than this reads as a staircase on a phone, so further levels share the last indent. */
private const val MAX_OUTLINE_DEPTH = 5

/** A title as one line of at most [MAX_TITLE_CHARS]: runs of whitespace (and line breaks) become one space. */
fun cleanOutlineTitle(raw: String): String = raw.replace(WHITESPACE_RUN, " ").trim().take(MAX_TITLE_CHARS)

/**
 * The headings of [blocks] in reading order, or empty when the author never used heading styles.
 * A heading with no visible words is left out, and one that wraps or breaks shows as a single line.
 */
fun officeOutline(blocks: List<OfficeBlock>): List<OutlineEntry> {
    // `depth` holds the heading's own level here; it is made relative below, once the shallowest is known.
    val found = blocks.mapIndexedNotNull { index, block ->
        val heading = block as? OfficeBlock.Heading ?: return@mapIndexedNotNull null
        val title = cleanOutlineTitle(heading.text)
        if (title.isEmpty()) null else OutlineEntry(index, heading.level, title)
    }
    // A document that opens at Heading 2 is not indented for a level above that it never uses.
    val shallowest = found.minOfOrNull { it.depth } ?: return found
    return found.map { it.copy(depth = (it.depth - shallowest).coerceIn(0, MAX_OUTLINE_DEPTH)) }
}
