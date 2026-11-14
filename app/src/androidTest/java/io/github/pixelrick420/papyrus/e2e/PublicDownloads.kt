package io.github.pixelrick420.papyrus.e2e

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

// Picking a file hands the app a persistable grant, and only the system picker can make one.
internal object PublicDownloads {

    private const val PREFIX = "papyrus-e2e-"

    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

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

    fun removeAll() {
        if (!available) return
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%"),
        )
    }
}
