package com.papyrus.app.e2e

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** A file the test put where Papyrus can read it, and the `content:` URI another app would hand over for it. */
internal class Fixture(val name: String, val uri: Uri)

/**
 * Documents are built in memory and written when a test asks for them: a binary fixture cannot be reviewed
 * in a diff.
 *
 * They are served by the app's own FileProvider, which only shares `filesDir/imports`. A subfolder keeps them
 * apart from the library's own copies, which sit directly in it, so saving one to the library copies it, as it
 * would a document from any other app.
 */
internal object Fixtures {

    fun text(name: String, body: String): Fixture = bytes(name, body.toByteArray(Charsets.UTF_8))

    fun bytes(name: String, content: ByteArray): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "imports/$FOLDER").apply { mkdirs() }
        File(folder, name).writeBytes(content)
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.fileprovider")
            .appendPath("imports")
            .appendPath(FOLDER)
            .appendPath(name)
            .build()
        return Fixture(name, uri)
    }

    private const val FOLDER = "fixtures"
}
