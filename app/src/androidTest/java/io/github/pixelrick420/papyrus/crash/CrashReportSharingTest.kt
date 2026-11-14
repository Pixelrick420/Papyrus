package io.github.pixelrick420.papyrus.crash

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.MainApplication
import io.github.pixelrick420.papyrus.data.shareIntent
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashReportSharingTest {

    private val app = ApplicationProvider.getApplicationContext<MainApplication>()

    private val authority = "${app.packageName}.fileprovider"

    private val made = mutableListOf<File>()

    private lateinit var report: File

    @Before
    fun writeAReport() {
        val folder = CrashReportStore.directory(app).apply { mkdirs() }
        report = File(folder, "crash-1700000000000-sharing.json").apply { writeText(BODY) }
        made += report
    }

    @After
    fun removeWhatWasWritten() {
        made.forEach { it.delete() }
        // Beside the shared folders, so they are emptied here and their parents are the app's own.
        listOf("crash-reports-extra", "imports-extra").forEach { File(app.filesDir, it).deleteRecursively() }
    }

    @Test
    fun aReportIsSharedAsAContentUriThatReadsBackItsBytes() {
        val shared = resolve(report)

        assertEquals("content", shared.scheme)
        assertEquals(authority, shared.authority)
        val read = app.contentResolver.openInputStream(shared)?.use { it.readBytes() }
        assertEquals(BODY, read?.decodeToString())
    }

    @Test
    fun theShareGrantsReadAccessAndNeverWrite() {
        val intent = shareIntent(resolve(report), "application/json")

        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun aFileOutsideTheSharedFoldersIsNeverGivenAContentUri() {
        val strays = listOf(
            stray(File(app.filesDir, "secret.txt")),
            stray(File(app.cacheDir, "cached.txt")),
            // Names that start like a shared folder, which a prefix match would take for one of them.
            stray(File(app.filesDir, "crash-reports-extra/crash-1.json")),
            stray(File(app.filesDir, "imports-extra/notes.txt")),
        )

        for (file in strays) {
            assertEquals("${file.path} was given a content URI", "file", resolve(file).scheme)
        }
    }

    @Test
    fun theProviderIsConfiguredForOnlyImportsAndCrashReports() {
        val inside = listOf(report, File(app.filesDir, "imports/notes.txt"))
        for (file in inside) {
            assertEquals("content", FileProvider.getUriForFile(app, authority, file).scheme)
        }
        val outside = listOf(File(app.filesDir, "secret.txt"), File(app.cacheDir, "cached.txt"), app.getDatabasePath("any.db"))
        for (file in outside) {
            assertThrows(file.path, IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, authority, file) }
        }
    }

    private fun resolve(file: File): Uri = runBlocking { app.repository.resolveShareUri(Uri.fromFile(file)) }

    private fun stray(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("private")
        made += file
        return file
    }

    private companion object {
        const val BODY = """{"REPORT_ID":"sharing","STACK_TRACE":"java.lang.IllegalStateException: boom"}"""
    }
}
