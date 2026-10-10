package com.papyrus.app.crash

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderException
import org.acra.sender.ReportSenderFactory
import java.io.File
import java.io.IOException
import java.util.UUID

/** Persists each crash report as a JSON file in app-private storage. Nothing is transmitted. */
class LocalFileReportSender : ReportSender {
    override fun send(context: Context, errorContent: CrashReportData) {
        try {
            val dir = CrashReportStore.directory(context).apply { mkdirs() }
            val id = errorContent.getString(ReportField.REPORT_ID) ?: UUID.randomUUID().toString()
            File(dir, "crash-${System.currentTimeMillis()}-$id.json").writeText(errorContent.toJSON())
            CrashReportStore.prune(context)
        } catch (e: IOException) {
            throw ReportSenderException("Unable to store crash report locally", e)
        }
    }
}

class LocalFileReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender = LocalFileReportSender()
    override fun enabled(config: CoreConfiguration): Boolean = true
}

object CrashReportStore {
    private const val MAX_REPORTS = 20

    fun directory(context: Context): File = File(context.filesDir, "crash-reports")

    fun list(context: Context): List<File> =
        directory(context).listFiles { f -> f.extension == "json" }?.sortedByDescending { it.name }.orEmpty()

    fun prune(context: Context) = list(context).drop(MAX_REPORTS).forEach { it.delete() }
}
