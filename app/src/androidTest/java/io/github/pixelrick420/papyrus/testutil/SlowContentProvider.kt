package io.github.pixelrick420.papyrus.testutil

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Test-only provider whose openFile sleeps, for exercising slow cross-process reads. */
class SlowContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val delay = openFileDelayMillis
        if (delay > 0L) {
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FileNotFoundException("openFile interrupted").apply { initCause(e) }
            }
        }
        return ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun getType(uri: Uri): String = "text/plain"

    private val payload: File by lazy {
        File(requireContext().cacheDir, "slow-content.txt").apply { writeText("slow provider payload") }
    }

    companion object {
        const val AUTHORITY = "io.github.pixelrick420.papyrus.test.slowcontent"
        const val URI = "content://$AUTHORITY/file"

        var openFileDelayMillis: Long = 0L
    }
}
