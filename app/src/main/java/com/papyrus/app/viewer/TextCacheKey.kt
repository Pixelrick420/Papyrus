package com.papyrus.app.viewer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

// A file that was edited (new modified time or size) is never answered from what it used to say.
data class TextCacheKey(val uri: String, val lastModified: Long, val sizeBytes: Long)

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
    return TextCacheKey(uri.toString(), stat.first, stat.second)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}
