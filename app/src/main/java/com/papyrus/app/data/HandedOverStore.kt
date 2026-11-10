package com.papyrus.app.data

// Several are live at once (documentLaunchMode="intoExisting"), so adding one must never drop another.
// Every access takes the lock (opens arrive on IO threads); past [capacity] the least recently used entry goes.
internal class HandedOverStore<T>(private val capacity: Int = DEFAULT_CAPACITY) {

    // Access order, so a document being read stays while ones opened and forgotten age out.
    private val entries = LinkedHashMap<Long, T>(INITIAL_SIZE, LOAD_FACTOR, true)
    private var nextId = FIRST_ID

    @Synchronized
    fun add(build: (id: Long) -> T): Long {
        val id = nextId--
        entries[id] = build(id)
        trim()
        return id
    }

    @Synchronized
    operator fun get(id: Long): T? = entries[id]

    @Synchronized
    operator fun set(id: Long, entry: T) {
        entries[id] = entry
        trim()
    }

    private fun trim() {
        while (entries.size > capacity) {
            val oldest = entries.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    companion object {
        /** Ids are negative (Room's are positive) and -1 is the viewer's sentinel, so counting starts at -2. */
        const val FIRST_ID = -2L
        const val DEFAULT_CAPACITY = 16
        private const val INITIAL_SIZE = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
