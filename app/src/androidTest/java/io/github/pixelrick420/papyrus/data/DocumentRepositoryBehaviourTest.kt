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
        File(importsDir(), REPICK_DIR).deleteRecursively()
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

        val result = repo.register(uri)

        assertTrue(result is Registration.Encrypted)
        assertTrue(result.id < 0)
        assertNull(database.documentDao().getByUri(uri.toString()))
        assertEquals(0, persistCalls)
    }

    @Test
    fun aRefusedGrantIsHandedOverAndReportedAsRefused() = runBlocking {
        val repo = repository(encryptionCheck = { _, _, _ -> false }, persistGrant = { false })
        val (_, uri) = sourceFile("refused.pptx")

        val result = repo.register(uri)

        assertTrue(result is Registration.GrantRefused)
        assertTrue(result.id < 0)
        assertNull(database.documentDao().getByUri(uri.toString()))
    }

    @Test
    fun aGrantedFileIsAddedWithItsRowId() = runBlocking {
        val repo = repository(encryptionCheck = { _, _, _ -> false }, persistGrant = { true })
        val (_, uri) = sourceFile("granted.pptx")

        val result = repo.register(uri)

        assertTrue(result is Registration.Added)
        assertEquals(database.documentDao().getByUri(uri.toString())!!.id, result.id)
    }

    @Test
    fun regrantOfAHandedOverDocumentKeepsTheOldGrantWhileALibraryRowStillUsesIt() = runBlocking {
        val released = mutableListOf<Uri>()
        val repo = repository(releaseGrant = { released += it })
        val (oldFile, oldUri) = sourceFile("shared.pptx")
        val newUri = repickedUri("shared.pptx")
        database.documentDao().insertIgnore(sourceEntity(oldUri, oldFile).copy(id = EXISTING_ID))
        val handedOver = repo.getById(repo.openHandedOver(oldUri))!!

        assertTrue(repo.regrant(handedOver, newUri))

        assertTrue(released.isEmpty())
        assertEquals(newUri.toString(), repo.getById(handedOver.id)!!.uri)
    }

    @Test
    fun regrantOfAHandedOverDocumentReleasesTheOldGrantWhenNothingElseUsesIt() = runBlocking {
        val released = mutableListOf<Uri>()
        val repo = repository(releaseGrant = { released += it })
        val (_, oldUri) = sourceFile("alone.pptx")
        val newUri = repickedUri("alone.pptx")
        val handedOver = repo.getById(repo.openHandedOver(oldUri))!!

        assertTrue(repo.regrant(handedOver, newUri))

        assertEquals(listOf(oldUri), released)
    }

    @Test
    fun regrantOfALibraryRowRebindsItAndReleasesTheOldGrant() = runBlocking {
        val released = mutableListOf<Uri>()
        val repo = repository(persistGrant = { true }, releaseGrant = { released += it })
        val (oldFile, oldUri) = sourceFile("moved.pptx")
        val newUri = repickedUri("moved.pptx")
        val dao = database.documentDao()
        dao.insertIgnore(sourceEntity(oldUri, oldFile).copy(id = EXISTING_ID))

        assertTrue(repo.regrant(dao.getById(EXISTING_ID)!!, newUri))

        assertEquals(newUri.toString(), dao.getById(EXISTING_ID)!!.uri)
        assertEquals(listOf(oldUri), released)
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
    fun markOpenedRefreshesTheSizeOfAChangedSource() = runBlocking {
        val (source, uri) = sourceFile("changed.pptx")
        val dao = database.documentDao()
        val id = dao.insertIgnore(sourceEntity(uri, source).copy(sizeBytes = 1))

        source.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        repository().markOpened(id)

        val row = dao.getById(id)!!
        assertEquals(source.length(), row.sizeBytes)
        assertTrue(row.lastOpenedAt > 0)
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

    // The same name in another folder: a re-pick of the file must match by name, so it needs its own URI.
    private fun repickedUri(name: String): Uri {
        val dir = File(importsDir(), REPICK_DIR)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        return FileProvider.getUriForFile(
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
        const val REPICK_DIR = "repick"
    }
}
