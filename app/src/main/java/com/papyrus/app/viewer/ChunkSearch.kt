package com.papyrus.app.viewer

/**
 * One match of a search through chunks. [globalOffset] is where it starts in the document as the chunks
 * read together, a `\n` between each pair; [offset] is where it starts in its own [chunk], which is the one
 * holding that first character, and is the chunk's length when the match begins at the `\n` after it.
 */
class ChunkMatch(val chunk: Int, val offset: Int, val globalOffset: Long)

/**
 * Every match of [query] in [chunks], one chunk at a time, in the order the document reads. Matching
 * ignores case and, as an editor does, advances past a whole match, so "aa" in "aaaa" is two. A match
 * may run over a chunk's end: the last `query.length - 1` characters are carried into the next chunk's
 * search. Chunks end at a line break, which is not in them, so that takes a query holding a `\n`; one
 * that does not is searched chunk by chunk with nothing carried. [limit] caps the list, and [shouldStop]
 * is polled once per chunk.
 */
fun searchChunks(
    chunks: List<String>,
    query: String,
    limit: Int = Int.MAX_VALUE,
    shouldStop: () -> Boolean = { false },
): List<ChunkMatch> {
    if (query.isBlank() || limit <= 0) return emptyList()
    val matches = ArrayList<ChunkMatch>()
    val carries = '\n' in query
    val keep = query.length - 1
    // The text not yet searched past, where it starts in the document, and the chunks it came from.
    var carry = ""
    var carryStart = 0L
    val recent = RecentChunks()
    var documentStart = 0L

    for (index in chunks.indices) {
        if (matches.size >= limit || shouldStop()) break
        val chunk = chunks[index]
        if (!carries) {
            var from = 0
            while (matches.size < limit) {
                val at = chunk.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                matches += ChunkMatch(index, at, documentStart + at)
                from = at + query.length
            }
            documentStart += chunk.length + 1
            continue
        }

        recent.add(index, documentStart)
        // The `\n` between two chunks is a character of the document, so a query can hold it.
        val text = if (index == 0) chunk else "$carry\n$chunk"
        val textStart = if (index == 0) 0L else carryStart
        var from = 0
        while (matches.size < limit) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            val global = textStart + at
            val owner = recent.chunkAt(global)
            matches += ChunkMatch(owner.first, (global - owner.second).toInt(), global)
            from = at + query.length
        }
        val tailFrom = maxOf(from, text.length - keep)
        carry = text.substring(tailFrom)
        carryStart = textStart + tailFrom
        recent.dropBefore(carryStart)
        documentStart += chunk.length + 1
    }
    return matches
}

/** The chunks a search still needs the start of, to say which one a match began in. */
private class RecentChunks {
    private val indexes = ArrayList<Int>()
    private val starts = ArrayList<Long>()

    fun add(index: Int, start: Long) {
        indexes += index
        starts += start
    }

    /** The chunk holding [position] and where it starts; the `\n` after a chunk is the chunk's. */
    fun chunkAt(position: Long): Pair<Int, Long> {
        var i = starts.lastIndex
        while (i > 0 && starts[i] > position) i--
        return indexes[i] to starts[i]
    }

    /** Forgets the chunks that end before [position], keeping the one holding it. */
    fun dropBefore(position: Long) {
        var drop = 0
        while (drop < starts.size - 1 && starts[drop + 1] <= position) drop++
        if (drop == 0) return
        indexes.subList(0, drop).clear()
        starts.subList(0, drop).clear()
    }
}
