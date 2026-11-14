package io.github.pixelrick420.papyrus.e2e

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal class Fixture(val name: String, val uri: Uri)

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
