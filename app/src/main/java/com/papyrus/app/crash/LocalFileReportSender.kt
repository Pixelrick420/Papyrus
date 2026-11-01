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
import java.util.regex.Pattern

class LocalFileReportSender : ReportSender {
    override fun send(context: Context, errorContent: CrashReportData) {
        try {
            val dir = CrashReportStore.directory(context).apply { mkdirs() }
            val id = errorContent.getString(ReportField.REPORT_ID) ?: UUID.randomUUID().toString()
            // Stack traces can embed absolute file paths (e.g. in an exception message), which would
            // reveal which documents the user had open. Redact app-internal paths before persisting.
            redactDocumentPaths(errorContent, context.filesDir)
            File(dir, "crash-${System.currentTimeMillis()}-$id.json").writeText(errorContent.toJSON())
            CrashReportStore.prune(context)
        } catch (e: IOException) {
            throw ReportSenderException("Unable to store crash report locally", e)
        }
    }

    /** Replaces app-internal absolute paths in the stack trace so crash reports never name the user's documents. */
    private fun redactDocumentPaths(errorContent: CrashReportData, filesDir: File) {
        val stackTrace = errorContent.getString(ReportField.STACK_TRACE) ?: return
        val root = filesDir.absolutePath
        val sanitized = stackTrace.replace(Regex(Pattern.quote(root)), "[redacted]")
        if (sanitized != stackTrace) {
            errorContent.put(ReportField.STACK_TRACE, sanitized)
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

    fun list(context: Context): List<File> = list(directory(context))

    internal fun list(dir: File): List<File> =
        dir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.name }.orEmpty()

    fun prune(context: Context) = prune(directory(context))

    internal fun prune(dir: File) = list(dir).drop(MAX_REPORTS).forEach { it.delete() }

    fun isInside(context: Context, file: File): Boolean = isInside(directory(context), file)

    internal fun isInside(dir: File, file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == dir.canonicalFile
    }.getOrDefault(false)

    fun delete(file: File): Boolean = file.delete()

    fun timestamp(file: File): Long {
        val name = file.name
        if (name.startsWith("crash-")) {
            val parts = name.removePrefix("crash-").split("-")
            parts.firstOrNull()?.toLongOrNull()?.let { return it }
        }
        return file.lastModified()
    }
}
