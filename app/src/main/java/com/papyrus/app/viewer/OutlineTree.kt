package com.papyrus.app.viewer

/**
 * An outline is a flat list in reading order with a [OutlineEntry.depth] on each entry, so the tree
 * is implicit: an entry has children when the next one is deeper. These functions fold and unfold it
 * by position in that list, which keeps the screen free of tree bookkeeping and this testable.
 */

/** A long outline starts folded: past this many entries the top level is what a reader can browse. */
internal const val AUTO_FOLD_ABOVE = 60

fun hasChildren(entries: List<OutlineEntry>, position: Int): Boolean =
    position in 0 until entries.lastIndex && entries[position + 1].depth > entries[position].depth

/**
 * The positions of [entries] to show when the entries in [folded] are collapsed: everything except
 * what lies under a folded entry. A folded entry that is itself hidden by an outer fold changes
 * nothing, and a position in [folded] that is not a parent is ignored.
 */
fun visibleOutline(entries: List<OutlineEntry>, folded: Set<Int>): List<Int> {
    val shown = ArrayList<Int>(entries.size)
    // Entries deeper than this are hidden; null while nothing above is folded.
    var hiddenBelow: Int? = null
    for (position in entries.indices) {
        val depth = entries[position].depth
        if (hiddenBelow != null) {
            if (depth > hiddenBelow) continue
            hiddenBelow = null
        }
        shown.add(position)
        if (position in folded && hasChildren(entries, position)) hiddenBelow = depth
    }
    return shown
}

/** Which entries start folded: none for a short outline, every parent for a long one. */
fun initialFolds(entries: List<OutlineEntry>): Set<Int> =
    if (entries.size <= AUTO_FOLD_ABOVE) emptySet() else entries.indices.filter { hasChildren(entries, it) }.toSet()
