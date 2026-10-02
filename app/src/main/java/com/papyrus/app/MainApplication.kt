package com.papyrus.app

import android.app.Application
import android.content.Context
import com.papyrus.app.data.AppDatabase
import com.papyrus.app.data.DocumentRepository
import com.papyrus.app.data.ThumbnailLoader
import org.acra.ACRA
import org.acra.ReportField
import org.acra.data.StringFormat
import org.acra.ktx.initAcra

class MainApplication : Application() {

    /** Manual DI: lazily created so the ACRA sender process never opens the database. */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: DocumentRepository by lazy { DocumentRepository(this, database.documentDao()) }

    /** Application-scoped so the bitmap cache survives navigation and rotation. */
    val thumbnailLoader: ThumbnailLoader by lazy { ThumbnailLoader(this) }

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
    }
}
