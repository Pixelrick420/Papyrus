package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TextCacheKeyTest {

    private val lookup = TextCacheLookupKey("content://doc/1", 123L, 456L)

    @Test
    fun aWriteKeyStatsTheDescriptorOnlyWhenItIsCreated() {
        var stats = 0
        val key = textCacheKey(lookup) { stats++; 789L }

        assertEquals(789L, key.contentRevision)
        assertEquals(1, stats)
    }

    @Test
    fun aMetadataLookupFindsTheNewestWriteKey() {
        val keys = TextCacheWriteKeys<TextCacheKey>()
        assertNull(keys.get(lookup))

        val first = textCacheKey(lookup) { 1L }
        keys.put(lookup, first)
        assertSame(first, keys.get(lookup))

        val second = textCacheKey(lookup) { 2L }
        keys.put(lookup, second)
        assertSame(second, keys.get(lookup))
    }

    @Test
    fun differentMetadataDoesNotShareAWriteKey() {
        val keys = TextCacheWriteKeys<TextCacheKey>()
        val key = textCacheKey(lookup) { 1L }
        keys.put(lookup, key)

        assertNull(keys.get(lookup.copy(sizeBytes = 457L)))
        assertNull(keys.get(lookup.copy(lastModified = 124L)))
    }
}
