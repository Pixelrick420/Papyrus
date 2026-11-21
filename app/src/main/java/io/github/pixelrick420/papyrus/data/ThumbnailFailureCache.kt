package io.github.pixelrick420.papyrus.data

internal fun thumbnailCacheKey(document: DocumentEntity): String = "${document.id}@${document.sizeBytes}"

internal class ThumbnailFailureCache(
    private val clock: () -> Long,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val entries = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    fun get(key: String): ThumbnailResult? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt != Long.MAX_VALUE && entry.expiresAt <= clock()) {
            entries.remove(key)
            return null
        }
        return entry.result
    }

    fun put(key: String, result: ThumbnailResult, ttlMillis: Long) {
        require(result !is ThumbnailResult.Image)
        val expiresAt = if (ttlMillis == PERMANENT_TTL) Long.MAX_VALUE else clock() + ttlMillis
        entries[key] = Entry(result, expiresAt)
    }

    fun evictAll() = entries.clear()

    internal data class Entry(val result: ThumbnailResult, val expiresAt: Long)

    internal companion object {
        const val SHORT_TTL = 5 * 60 * 1_000L
        const val PERMANENT_TTL = Long.MAX_VALUE
        const val MAX_ENTRIES = 512
    }
}
