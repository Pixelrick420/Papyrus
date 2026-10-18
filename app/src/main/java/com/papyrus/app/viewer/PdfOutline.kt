package com.papyrus.app.viewer

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * A PDF's bookmarks (its outline) as the flat, depth-tagged list the outline sheet shows.
 *
 * The outline is author-supplied and PdfBox only parses what it is given, so nothing in it can be
 * trusted. A bookmark can point at itself through its sibling or child links, nest without end, run to
 * the hundreds of thousands, carry no title, or point at a page that does not exist; none of that may
 * hang the viewer, run it out of memory, or cost the reader the rest of the outline. So:
 *
 *  - every bookmark is visited once ([visited] compares the underlying dictionaries by identity), which
 *    ends any cycle however it loops;
 *  - at most [MAX_ENTRIES] are visited and [MAX_LEVELS] deep are descended, and what was read before a
 *    cap is hit is returned;
 *  - anything that throws while reading one bookmark costs that bookmark, never its siblings;
 *  - a bookmark with no readable title is left out but its children keep their place;
 *  - a bookmark that goes to no page is kept only if something below it does, as an entry that cannot
 *    be chosen ([NO_OUTLINE_TARGET]), so a chapter heading that merely groups its sections survives.
 *
 * It runs once per document, off the main thread; [PdfPageTextSource.outline] is the caller.
 */
object PdfOutlineReader {

    /** Bookmarks visited before giving up; a real book has a few thousand at the very most. */
    const val MAX_ENTRIES = 5_000

    /** Levels descended; real outlines stop at four or five, and recursion has to stay shallow. */
    const val MAX_LEVELS = 32

    /** The outline of [document], empty when it has none, or when it cannot be read at all. */
    fun read(document: PDDocument): List<OutlineEntry> {
        val outline = attempt { document.documentCatalog.documentOutline } ?: return emptyList()
        return Walk(Destinations(document)).siblings(attempt { outline.firstChild }, level = 0, depth = 0)
    }

    private class Walk(private val destinations: Destinations) {
        /** Identity, not equality: two distinct bookmarks may be equal dictionaries, and a loop revisits the same one. */
        private val visited: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())
        private var budget = MAX_ENTRIES

        /**
         * The entries of the sibling chain starting at [first], each followed by its own subtree.
         * [level] counts real nesting and bounds the recursion; [depth] is the indent the entries get,
         * which a skipped untitled bookmark does not add to.
         */
        fun siblings(first: PDOutlineItem?, level: Int, depth: Int): List<OutlineEntry> {
            val out = ArrayList<OutlineEntry>()
            var item = first
            while (item != null && budget > 0) {
                val dictionary = item.cosObject
                if (!visited.add(dictionary)) break // the chain came back to a bookmark already read
                budget--
                val current = item
                val title = cleanOutlineTitle(attempt { current.title }.orEmpty())
                val target = destinations.pageOf(current)
                val untitled = title.isEmpty()
                val below = if (level + 1 < MAX_LEVELS) {
                    attempt { siblings(current.firstChild, level + 1, if (untitled) depth else depth + 1) }.orEmpty()
                } else {
                    emptyList()
                }
                when {
                    untitled -> out.addAll(below)
                    target != null || below.isNotEmpty() -> {
                        out.add(OutlineEntry(target ?: NO_OUTLINE_TARGET, depth, title))
                        out.addAll(below)
                    }
                }
                item = attempt { current.nextSibling }
            }
            return out
        }
    }

    /** Turns a bookmark's destination into a page index of one document. */
    private class Destinations(private val document: PDDocument) {
        private val pageCount = attempt { document.numberOfPages } ?: 0

        /**
         * Each page's index by its dictionary, built once on first use. [com.tom_roush.pdfbox.pdmodel.PDPageTree.indexOf]
         * walks the page tree on every call, so doing that per bookmark is quadratic on a big book.
         */
        private val indexByPage: Map<COSDictionary, Int> by lazy {
            val map = IdentityHashMap<COSDictionary, Int>()
            attempt { document.pages.forEachIndexed { index, page -> map[page.cosObject] = index } }
            map
        }

        /** The page [item] goes to, or null: no destination, one that is not a page, or a page that is not there. */
        fun pageOf(item: PDOutlineItem): Int? {
            // The item's own destination first, then a GoTo action's; each read on its own, so one
            // that throws does not hide the other.
            attempt { item.destination }?.let { dest -> indexOf(dest)?.let { return it } }
            val action = attempt { item.action } as? PDActionGoTo ?: return null
            return attempt { action.destination }?.let { indexOf(it) }
        }

        private fun indexOf(destination: PDDestination): Int? = when (destination) {
            is PDPageDestination -> indexOf(destination)
            // One hop only: a name that resolves to another name is malformed, not something to chase.
            is PDNamedDestination -> named(destination.namedDestination)?.let { indexOf(it) }
            else -> null
        }

        private fun indexOf(destination: PDPageDestination): Int? {
            // `page` is null when the destination carries a page number instead of a page object. A page
            // object that is not in the tree is unresolved: its stale number is not a fallback.
            val pageObject = attempt { destination.page }
            val index = if (pageObject != null) indexByPage[pageObject.cosObject] else attempt { destination.pageNumber }
            return index?.takeIf { it in 0 until pageCount }
        }

        /** A named destination, from the name tree (PDF 1.2 and later) and then the catalog's old /Dests dictionary. */
        private fun named(name: String?): PDPageDestination? {
            if (name.isNullOrEmpty()) return null
            val catalog = attempt { document.documentCatalog } ?: return null
            attempt { catalog.names?.dests?.getValue(name) }?.let { return it }
            return attempt { catalog.dests?.getDestination(name) } as? PDPageDestination
        }
    }

    /** [block]'s result, or null if it threw: PdfBox throws unchecked on some malformed files, not only IOException. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        null
    }
}
