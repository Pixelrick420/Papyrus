package com.papyrus.app

import android.app.Application
import android.content.Context
import android.os.Process
import androidx.core.net.toUri
import com.papyrus.app.data.AppDatabase
import com.papyrus.app.data.DocumentRepository
import com.papyrus.app.data.DocumentTextExtractor
import com.papyrus.app.data.IndexingGate
import com.papyrus.app.data.ReaderPresence
import com.papyrus.app.data.SearchIndexer
import com.papyrus.app.data.SearchRepository
import com.papyrus.app.data.SettingsRepository
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.data.batterySaverFlow
import com.papyrus.app.tts.TtsSession
import com.papyrus.app.viewer.OFFICE_UNLOCK_DIR
import com.papyrus.app.viewer.PDF_UNLOCK_DIR
import com.papyrus.app.viewer.PdfPageSource
import com.papyrus.app.viewer.textCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Content search over the index [searchIndexer] fills. */
    val search: SearchRepository by lazy { SearchRepository(database.searchDao()) }

    /** Which screens are showing a document; the indexer stays out of their way, see [IndexingGate]. */
    val readerPresence = ReaderPresence()

    /**
     * One thread of its own at background priority, so extraction never takes the shared IO threads
     * from a viewer that is rendering, and the system schedules it behind the foreground work.
     */
    private val indexerDispatcher by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "papyrus-indexer").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * Extracts document text for the index in the background, using the same readers the viewer
     * opens documents with, at low priority and only while [IndexingGate] is open: not while a
     * document is being read, not under battery saver, and not once the setting is off. Not started
     * here: [onCreate] starts it off the main thread.
     */
    val searchIndexer: SearchIndexer by lazy {
        val extractor = DocumentTextExtractor(this, indexerDispatcher) { uri ->
            contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for $uri")
        }
        val gate = IndexingGate(
            enabled = settings.settings.map { it.contentIndexing },
            reading = readerPresence.reading,
            batterySaver = batterySaverFlow(this),
        )
        SearchIndexer(
            dao = database.searchDao(),
            extract = extractor::extract,
            // Only a document coming, going or changing size is worth a pass; a star or an open is not.
            library = repository.documents.map { rows -> rows.map { it.id to it.sizeBytes } }.distinctUntilChanged(),
            lastModified = { document -> textCacheKey(this, document.uri.toUri())?.lastModified },
            mayIndex = gate.open,
            startDelayMillis = INDEX_START_DELAY_MILLIS,
        )
    }

    /**
     * Work that must finish even if the screen that started it is gone (e.g. committing a removal
     * whose undo window was still open). Never cancelled: it lives and dies with the process.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Application-scoped so the bitmap cache survives navigation and rotation. */
    val thumbnailLoader: ThumbnailLoader by lazy { ThumbnailLoader(this) }

    /** Application-scoped TTS session for background playback. */
    val ttsSession: TtsSession by lazy { TtsSession() }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Reports never leave the device: the only enabled sender is LocalFileReportSender, which
        // writes JSON into filesDir/crash-reports.
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON
            // Privacy-minimal: no LOGCAT, no shared prefs, no device identifiers.
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
        sweepUnlocked()
        // Off the main thread: the first thing indexing does is open the database, and a kill
        // between the two is harmless because the queue lives in that database.
        applicationScope.launch { searchIndexer.start(this) }
    }

    /** Trims the page and thumbnail caches; the bitmaps are rebuilt on demand, so trimming costs only a re-render. */
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

        /** Indexing waits this long after launch, so it does not compete with the first screen. */
        const val INDEX_START_DELAY_MILLIS = 3_000L
    }
}
