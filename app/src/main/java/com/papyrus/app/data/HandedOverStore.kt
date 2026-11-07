package com.papyrus.app.data

/**
 * The documents other apps handed over, by the negative ids they open under. In memory only. Several are
 * live at once: with `documentLaunchMode="intoExisting"` a second external file opens beside the first, so
 * adding one must never drop another. Opens arrive on IO threads, so every access takes the lock. What bounds
 * it is [capacity]: past that, the entry used longest ago goes, never the whole set.
 */
internal class HandedOverStore<T>(private val capacity: Int = DEFAULT_CAPACITY) {

    // Access order, so a document being read stays while ones opened and forgotten age out.
    private val entries = LinkedHashMap<Long, T>(INITIAL_SIZE, LOAD_FACTOR, true)
    private var nextId = FIRST_ID

    /** Stores what [build] makes for a fresh id, and returns that id. */
    @Synchronized
    fun add(build: (id: Long) -> T): Long {
        val id = nextId--
        entries[id] = build(id)
        trim()
        return id
    }

    @Synchronized
    operator fun get(id: Long): T? = entries[id]

    /** Replaces the entry under [id], as a re-grant does; an entry that has aged out is stored again. */
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
