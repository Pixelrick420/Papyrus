package io.github.pixelrick420.papyrus.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryBehaviourTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var settings: SettingsRepository
    private var importsBefore: Set<String> = emptySet()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        settings = SettingsRepository(context)
        settings.setAutosaveEncrypted(false)
        importsBefore = importsDir().list().orEmpty().toSet()
    }

    @After
    fun tearDown() {
        importsDir().listFiles().orEmpty()
            .filterNot { it.name in importsBefore }
            .forEach { it.delete() }
        database.close()
    }

    @Test
    fun encryptedPptxWithAutosaveOffIsHandedOver() = runBlocking {
        var persistCalls = 0
        val repo = repository(
            encryptionCheck = { format, _, _ -> format == DocumentFormat.PPTX },
            persistGrant = { persistCalls++; true },
        )
        val (_, uri) = sourceFile("encrypted.pptx")

        val id = repo.register(uri)

        assertTrue(id < 0)
        assertNull(database.documentDao().getByUri(uri.toString()))
        assertEquals(0, persistCalls)
    }

    @Test
    fun saveToLibraryRepointsExistingRowAndKeepsCopyWhenReleaseFails() = runBlocking {
        val (source, uri) = sourceFile("existing.pptx")
        val dao = database.documentDao()
        dao.insertIgnore(
            DocumentEntity(
                id = EXISTING_ID,
                uri = uri.toString(),
                title = source.name,
                format = DocumentFormat.PPTX,
                sizeBytes = source.length(),
                createdAt = 0,
                lastOpenedAt = 0,
            ),
        )
        val repo = repository(releaseGrant = { throw IllegalStateException("grant lost") })

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.saveToLibrary(sourceEntity(uri, source)) }
        }

        val row = dao.getById(EXISTING_ID)
        assertNotNull(row)
        assertTrue(row!!.uri.startsWith("file:"))
        val copy = File(Uri.parse(row.uri).path!!)
        assertTrue(copy.isFile)
        assertEquals(source.length(), row.sizeBytes)
        assertEquals(row.sizeBytes, copy.length())
    }

    @Test
    fun saveToLibraryInsertsRowForNewSource() = runBlocking {
        val (source, uri) = sourceFile("new-source.pptx")
        var releaseCalls = 0
        var persistCalls = 0
        val repo = repository(
            persistGrant = { persistCalls++; true },
            releaseGrant = { releaseCalls++ },
        )

        val id = repo.saveToLibrary(sourceEntity(uri, source))

        assertTrue(id > 0)
        val row = database.documentDao().getById(id)
        assertNotNull(row)
        assertTrue(row!!.uri.startsWith("file:"))
        val copy = File(Uri.parse(row.uri).path!!)
        assertTrue(copy.isFile)
        assertEquals(row.sizeBytes, copy.length())
        assertEquals(0, releaseCalls)
        assertEquals(0, persistCalls)
    }

    private fun repository(
        encryptionCheck: ((DocumentFormat, Uri, Long) -> Boolean)? = null,
        persistGrant: ((Uri) -> Boolean)? = null,
        releaseGrant: ((Uri) -> Unit)? = null,
    ) = DocumentRepository(
        context,
        database.documentDao(),
        database.readingProgressDao(),
        database.bookmarkDao(),
        database.highlightDao(),
        database.pdfOutlineDao(),
        database.searchDao(),
        settings,
        encryptionCheck = encryptionCheck,
        persistGrant = persistGrant,
        releaseGrant = releaseGrant,
    )

    private fun sourceFile(name: String): Pair<File, Uri> {
        val dir = importsDir()
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        return file to FileProvider.getUriForFile(
            context,
            "${context.packageName}${DocumentRepository.FILE_PROVIDER_SUFFIX}",
            file,
        )
    }

    private fun sourceEntity(uri: Uri, source: File) = DocumentEntity(
        uri = uri.toString(),
        title = source.name,
        format = DocumentFormat.PPTX,
        sizeBytes = source.length(),
        createdAt = 0,
        lastOpenedAt = 0,
    )

    private fun importsDir(): File = File(context.filesDir, "imports")

    private companion object {
        const val EXISTING_ID = 42L
    }
}
