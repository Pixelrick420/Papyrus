package io.github.pixelrick420.papyrus.e2e

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal class Fixture(val name: String, val uri: Uri)

internal object Fixtures {

    fun text(name: String, body: String): Fixture = bytes(name, body.toByteArray(Charsets.UTF_8))

    fun bytes(name: String, content: ByteArray): Fixture {
        file(name).writeBytes(content)
        return uriFor(name)
    }

    // Streamed, so a 50 MB fixture does not need 50 MB of heap in the test process.
    fun largeText(name: String, lines: Int, lineBytes: Int = 64): Fixture {
        file(name).bufferedWriter().use { out ->
            repeat(lines) { index ->
                val label = "row-${(index + 1).toString().padStart(8, '0')}"
                out.write(label)
                repeat((lineBytes - label.length - 1).coerceAtLeast(0)) { out.write("x") }
                out.write("\n")
            }
        }
        return uriFor(name)
    }

    private fun file(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "imports/$FOLDER").apply { mkdirs() }
        return File(folder, name)
    }

    private fun uriFor(name: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
