package io.github.pixelrick420.papyrus.viewer

// Every call locks: text is read on the IO pool and asked for from the main thread. [sizeOf] must give the same answer for a value every time.
class BoundedCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    // Access order, so iterating from the front visits the least recently used entry first.
    private val map = LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    // A value larger than the whole budget is not kept: it would push out everything else and then itself.
    @Synchronized
    fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxBytes) return
        map[key] = value
        bytes += size
        val oldest = map.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            bytes -= sizeOf(oldest.next().value)
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0L
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
