package com.papyrus.app.e2e

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Files in the device's Downloads folder, which is where the system picker looks for them.
 *
 * Picking a file hands the app a persistable grant, and only the system picker can make one, so the files a test
 * picks have to be somewhere the picker can list. Every name carries [PREFIX], and a run removes only those.
 */
internal object PublicDownloads {

    private const val PREFIX = "papyrus-e2e-"

    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    /** The Downloads collection exists from Android 10; a run on anything older skips what needs it. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Writes [content] as [name] and returns the name the picker will show, which carries the prefix. */
    fun put(name: String, mimeType: String, content: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, PREFIX + name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("Could not create $name in Downloads")
        val stream = resolver.openOutputStream(uri) ?: throw AssertionError("Could not write $name in Downloads")
        stream.use { it.write(content) }
        // A leftover of the same name makes the system pick another, so the name is read back, not assumed.
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: PREFIX + name
    }

    /** Removes the files earlier calls made. */
    fun removeAll() {
        if (!available) return
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%"),
        )
    }
}
