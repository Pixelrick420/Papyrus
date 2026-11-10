package com.papyrus.app.viewer

class ChunkMatch(val chunk: Int, val offset: Int, val globalOffset: Long)

// A match may run over a chunk's end: the last `query.length - 1` characters are carried into the next search.
// Chunks end at a line break that is not in them, so that is how a query holding a line break matches.
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

private class RecentChunks {
    private val indexes = ArrayList<Int>()
    private val starts = ArrayList<Long>()

    fun add(index: Int, start: Long) {
        indexes += index
        starts += start
    }

    fun chunkAt(position: Long): Pair<Int, Long> {
        var i = starts.lastIndex
        while (i > 0 && starts[i] > position) i--
        return indexes[i] to starts[i]
    }

    fun dropBefore(position: Long) {
        var drop = 0
        while (drop < starts.size - 1 && starts[drop + 1] <= position) drop++
        if (drop == 0) return
        indexes.subList(0, drop).clear()
        starts.subList(0, drop).clear()
    }
}
