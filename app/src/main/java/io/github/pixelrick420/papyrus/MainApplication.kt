package io.github.pixelrick420.papyrus

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.net.toUri
import io.github.pixelrick420.papyrus.data.AppDatabase
import io.github.pixelrick420.papyrus.data.DocumentRepository
import io.github.pixelrick420.papyrus.data.DocumentTextExtractor
import io.github.pixelrick420.papyrus.data.IndexingGate
import io.github.pixelrick420.papyrus.data.ReaderPresence
import io.github.pixelrick420.papyrus.data.SearchIndexer
import io.github.pixelrick420.papyrus.data.SearchRepository
import io.github.pixelrick420.papyrus.data.SettingsRepository
import io.github.pixelrick420.papyrus.data.ThumbnailLoader
import io.github.pixelrick420.papyrus.data.batterySaverFlow
import io.github.pixelrick420.papyrus.tts.TtsSession
import io.github.pixelrick420.papyrus.viewer.OFFICE_UNLOCK_DIR
import io.github.pixelrick420.papyrus.viewer.PDF_UNLOCK_DIR
import io.github.pixelrick420.papyrus.viewer.PdfPageSource
import io.github.pixelrick420.papyrus.viewer.textCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.acra.ReportField
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

class MainApplication : Application() {

    /** Manual DI: lazily created so the ACRA sender process never opens the database. */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val repository: DocumentRepository by lazy {
        DocumentRepository(
            this,
            database.documentDao(),
            database.readingProgressDao(),
            database.bookmarkDao(),
            database.highlightDao(),
            database.pdfOutlineDao(),
            database.searchDao(),
            settings,
        )
    }

    val search: SearchRepository by lazy { SearchRepository(database.searchDao()) }

    val readerPresence = ReaderPresence()

    // Own low-priority thread so extraction never takes IO threads from a rendering viewer.
    private val indexerDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "papyrus-indexer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    val searchIndexer: SearchIndexer by lazy {
        val extractor = DocumentTextExtractor(this, indexerDispatcher) { uri ->
            contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for $uri")
        }
        val enabled = settings.settings.map { it.contentIndexing }
        val gate = IndexingGate(
            enabled = enabled,
            reading = readerPresence.reading,
            batterySaver = batterySaverFlow(this),
        )
        SearchIndexer(
            dao = database.searchDao(),
            extract = extractor::extract,
            // Only a document coming, going or changing size is worth a pass; a star or an open is not.
            library = repository.documents.map { rows -> rows.map { it.id to it.sizeBytes } }.distinctUntilChanged(),
            lastModified = { document -> textCacheKey(this, document.uri.toUri())?.lastModified },
            enabled = enabled,
            mayIndex = gate.open,
            startDelayMillis = INDEX_START_DELAY_MILLIS,
        )
    }

    // Outlives any screen (e.g. committing a removal after its undo window); never cancelled.
    val applicationScope: CoroutineScope = backgroundScope { failure ->
        runCatching { ACRA.errorReporter.handleSilentException(failure) }
    }

    val thumbnailLoader: ThumbnailLoader by lazy { ThumbnailLoader(this) }

    val ttsSession: TtsSession by lazy { TtsSession() }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Reports never leave the device: the only sender writes JSON under filesDir/crash-reports.
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON
            reportContent = listOf(
                ReportField.REPORT_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.PRODUCT,
                ReportField.USER_CRASH_DATE,
                ReportField.STACK_TRACE,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ACRA runs its report pipeline in a separate ":acra" process; skip app setup there.
        if (ACRA.isACRASenderServiceProcess()) return
        // Taken before anything can save a copy, so only files from an earlier run count as old.
        val startedAt = System.currentTimeMillis()
        sweepUnlocked()
        // Off the main thread: the first thing indexing does is open the database, and a kill
        // between the two is harmless because the queue lives in that database.
        applicationScope.launch { searchIndexer.start(this) }
        applicationScope.launch { runCatching { repository.sweepOrphanedImports(startedAt) } }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (ACRA.isACRASenderServiceProcess() || level < TRIM_LEVEL_RUNNING_LOW) return
        trimCaches(evictAll = level >= TRIM_LEVEL_BACKGROUND)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (ACRA.isACRASenderServiceProcess()) return
        trimCaches(evictAll = true)
    }

    private fun trimCaches(evictAll: Boolean) {
        PdfPageSource.trimAll(evictAll)
        thumbnailLoader.trimMemory(evictAll)
    }

    /** A kill mid-view leaves a decrypted copy behind, so clear the whole directory on the next start. */
    private fun sweepUnlocked() {
        runCatching { File(cacheDir, PDF_UNLOCK_DIR).deleteRecursively() }
        runCatching { File(cacheDir, OFFICE_UNLOCK_DIR).deleteRecursively() }
    }

    private companion object {
        // The values of TRIM_MEMORY_RUNNING_LOW and TRIM_MEMORY_BACKGROUND, which newer SDKs deprecate.
        const val TRIM_LEVEL_RUNNING_LOW = 10
        const val TRIM_LEVEL_BACKGROUND = 40

        const val INDEX_START_DELAY_MILLIS = 3_000L
    }
}
