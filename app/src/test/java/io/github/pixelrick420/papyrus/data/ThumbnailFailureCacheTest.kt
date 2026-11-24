package io.github.pixelrick420.papyrus.data

import io.github.pixelrick420.papyrus.testutil.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailFailureCacheTest {

    private val clock = FakeClock()
    private val cache = ThumbnailFailureCache(clock.now, maxEntries = 2)

    @Test
    fun `a short lived failure expires`() {
        val failure = ThumbnailResult.Unavailable("missing")
        cache.put("1@10", failure, ThumbnailFailureCache.SHORT_TTL)

        assertEquals(failure, cache.get("1@10"))
        clock.advance(ThumbnailFailureCache.SHORT_TTL)
        assertNull(cache.get("1@10"))
    }

    @Test
    fun `a permanent failure survives a short ttl`() {
        val failure = ThumbnailResult.Unavailable("Unsupported")
        cache.put("1@10", failure, ThumbnailFailureCache.PERMANENT_TTL)

        clock.advance(ThumbnailFailureCache.SHORT_TTL * 2)
        assertEquals(failure, cache.get("1@10"))
    }

    @Test
    fun `an out of memory failure is retried after a short ttl`() {
        val spec = thumbnailFailureSpecFor(OutOfMemoryError())

        assertEquals(ThumbnailFailureCache.SHORT_TTL, spec.ttlMillis)

        cache.put("1@10", spec.result, spec.ttlMillis)
        clock.advance(spec.ttlMillis)

        assertNull(cache.get("1@10"))
    }

    @Test
    fun `the least recently used failure is evicted`() {
        val first = ThumbnailResult.NoAccess
        val second = ThumbnailResult.Unavailable("bad")
        val third = ThumbnailResult.Unavailable("worse")
        cache.put("1@10", first, ThumbnailFailureCache.PERMANENT_TTL)
        cache.put("2@10", second, ThumbnailFailureCache.PERMANENT_TTL)

        assertEquals(first, cache.get("1@10"))
        cache.put("3@10", third, ThumbnailFailureCache.PERMANENT_TTL)

        assertEquals(first, cache.get("1@10"))
        assertNull(cache.get("2@10"))
        assertEquals(third, cache.get("3@10"))
    }

    @Test
    fun `a size change gives a new key`() {
        val document = document(sizeBytes = 10)

        assertEquals("7@10", thumbnailCacheKey(document))
        assertNotEquals(thumbnailCacheKey(document), thumbnailCacheKey(document.copy(sizeBytes = 11)))

        cache.put(thumbnailCacheKey(document), ThumbnailResult.NoAccess, ThumbnailFailureCache.SHORT_TTL)
        assertNull(cache.get(thumbnailCacheKey(document.copy(sizeBytes = 11))))
    }

    @Test
    fun `evictAll clears remembered failures`() {
        cache.put("1@10", ThumbnailResult.NoAccess, ThumbnailFailureCache.PERMANENT_TTL)

        cache.evictAll()

        assertNull(cache.get("1@10"))
    }

    private fun document(sizeBytes: Long) = DocumentEntity(
        id = 7,
        uri = "content://test/7",
        title = "doc",
        format = DocumentFormat.TEXT,
        sizeBytes = sizeBytes,
        createdAt = 0,
        lastOpenedAt = 0,
    )
}
