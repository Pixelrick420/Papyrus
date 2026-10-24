package com.papyrus.app

import android.app.Application
import android.content.Context
import com.papyrus.app.data.AppDatabase
import com.papyrus.app.data.DocumentRepository
import com.papyrus.app.data.DocumentTextExtractor
import com.papyrus.app.data.SearchIndexer
import com.papyrus.app.data.SearchRepository
import com.papyrus.app.data.SettingsRepository
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.tts.TtsSession
import com.papyrus.app.viewer.OFFICE_UNLOCK_DIR
import com.papyrus.app.viewer.PDF_UNLOCK_DIR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.acra.ReportField
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import java.io.File
import java.io.FileNotFoundException

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
            database.searchDao(),
            settings,
        )
    }

    /** Content search over the index [searchIndexer] fills. */
    val search: SearchRepository by lazy { SearchRepository(database.searchDao()) }

    /**
     * Extracts document text for the index in the background, using the same readers the viewer
     * opens documents with. Not started here: [onCreate] starts it off the main thread.
     */
    val searchIndexer: SearchIndexer by lazy {
        val extractor = DocumentTextExtractor(this) { uri ->
            contentResolver.openInputStream(uri) ?: throw FileNotFoundException("no stream for $uri")
        }
        SearchIndexer(database.searchDao(), extractor::extract, repository.documents)
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

    /** A kill mid-view leaves a decrypted copy behind, so clear the whole directory on the next start. */
    private fun sweepUnlocked() {
        runCatching { File(cacheDir, PDF_UNLOCK_DIR).deleteRecursively() }
        runCatching { File(cacheDir, OFFICE_UNLOCK_DIR).deleteRecursively() }
    }
}
