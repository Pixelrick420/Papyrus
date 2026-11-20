package io.github.pixelrick420.papyrus.viewer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import java.util.concurrent.atomic.AtomicLong

// A file that was edited is never answered from what it used to say: the provider's timestamps can be coarse, so the descriptor's nanosecond stat and identity are folded in too.
data class TextCacheKey(
    val uri: String,
    val lastModified: Long,
    val sizeBytes: Long,
    // A provider without a statable descriptor gets a fresh value, which gives up reuse rather than risk stale text.
    val contentRevision: Long = 0L,
)

// [fromPlatform] keeps the platform engine's reading and PDFBox's apart: they differ in spacing and boxes.
data class PageTextKey(val document: TextCacheKey, val page: Int, val fromPlatform: Boolean)

// Only a content URI whose provider reports both a modified time and a size gets one; a file URI is never keyed, which keeps a protected PDF's decrypted copy out of anything that outlives its viewer.
fun textCacheKey(context: Context, uri: Uri): TextCacheKey? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val columns = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE)
    val stat = try {
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val modified = cursor.longOrNull(columns[0])
            val size = cursor.longOrNull(columns[1])
            if (modified == null || size == null) null else modified to size
        }
    } catch (ignored: Exception) {
        // Providers throw their own unchecked exceptions, and a revoked grant is a SecurityException.
        null
    }
    // A zero modified time is a provider that does not know, not a file from 1970.
    if (stat == null || stat.first <= 0L) return null
    val revision = descriptorRevision(context, uri) ?: unreusableRevision()
    return TextCacheKey(uri.toString(), stat.first, stat.second, revision)
}

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

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}
