package io.github.pixelrick420.papyrus.viewer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Provider metadata is enough to find a cached entry; a descriptor is only opened when an entry is written.
data class TextCacheLookupKey(
    val uri: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

// A file that was edited is never written under the old identity: the descriptor's nanosecond stat and identity are folded in too.
data class TextCacheKey(
    val uri: String,
    val lastModified: Long,
    val sizeBytes: Long,
    // A provider without a statable descriptor gets a fresh value, which gives up reuse rather than risk stale text.
    val contentRevision: Long = 0L,
)

// [fromPlatform] keeps the platform engine's reading and PDFBox's apart: they differ in spacing and boxes.
data class PageTextKey(val document: TextCacheKey, val page: Int, val fromPlatform: Boolean)

internal data class TextCacheMetadata(val modified: Long, val size: Long)

internal fun textCacheMetadata(context: Context, uri: Uri): TextCacheMetadata? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val columns = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE)
    val stat = try {
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val modified = cursor.longOrNull(columns[0])
            val size = cursor.longOrNull(columns[1])
            if (modified == null || size == null) null else TextCacheMetadata(modified, size)
        }
    } catch (ignored: Exception) {
        // Providers throw their own unchecked exceptions, and a revoked grant is a SecurityException.
        null
    }
    // A zero modified time is a provider that does not know, not a file from 1970.
    return stat?.takeIf { it.modified > 0L }
}

fun textCacheModified(context: Context, uri: Uri): Long? = textCacheMetadata(context, uri)?.modified

// Only a content URI whose provider reports both a modified time and a size gets one; a file URI is never keyed, which keeps a protected PDF's decrypted copy out of anything that outlives its viewer.
fun textCacheLookupKey(context: Context, uri: Uri): TextCacheLookupKey? {
    val stat = textCacheMetadata(context, uri) ?: return null
    return TextCacheLookupKey(uri.toString(), stat.modified, stat.size)
}

fun textCacheKey(context: Context, uri: Uri): TextCacheKey? {
    val lookup = textCacheLookupKey(context, uri) ?: return null
    return textCacheKey(context, uri, lookup)
}

internal fun textCacheKey(
    context: Context,
    uri: Uri,
    lookup: TextCacheLookupKey,
): TextCacheKey = textCacheKey(lookup) { descriptorRevision(context, uri) }

internal fun textCacheKey(
    lookup: TextCacheLookupKey,
    revision: () -> Long?,
): TextCacheKey = TextCacheKey(
    lookup.uri,
    lookup.lastModified,
    lookup.sizeBytes,
    revision() ?: unreusableRevision(),
)

private fun descriptorRevision(context: Context, uri: Uri): Long? = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        val st = Os.fstat(descriptor.fileDescriptor)
        var value = 0x9E3779B9L
        value = value * 31 + st.st_dev
        value = value * 31 + st.st_ino
        value = value * 31 + st.st_size
        value = value * 31 + st.st_mtime
        value = value * 31 + st.st_ctime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            value = value * 31 + st.st_mtim.tv_nsec
            value = value * 31 + st.st_ctim.tv_nsec
        }
        value
    }
} catch (ignored: Exception) {
    null
}

private val unreusableCounter = AtomicLong(System.nanoTime())

private fun unreusableRevision(): Long = unreusableCounter.incrementAndGet()

// The metadata address of the newest write key for a source, so a hit never has to stat its descriptor.
internal class TextCacheWriteKeys<K : Any> {
    private val keys = ConcurrentHashMap<TextCacheLookupKey, K>()

    fun get(lookup: TextCacheLookupKey): K? = keys[lookup]

    fun put(lookup: TextCacheLookupKey, key: K) {
        keys[lookup] = key
    }

    fun clear() = keys.clear()
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}
