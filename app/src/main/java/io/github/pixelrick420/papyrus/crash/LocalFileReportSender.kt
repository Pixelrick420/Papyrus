package io.github.pixelrick420.papyrus.crash

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderException
import org.acra.sender.ReportSenderFactory
import java.io.File
import java.util.regex.Pattern

class LocalFileReportSender : ReportSender {
    @Suppress("TooGenericExceptionCaught")
    override fun send(context: Context, errorContent: CrashReportData) {
        try {
            val dir = CrashReportStore.directory(context).apply { mkdirs() }
            val id = errorContent.getString(ReportField.REPORT_ID)
            // Any free-text field can carry a document path, not only the stack trace.
            redactReport(errorContent, listOf(context.filesDir.absolutePath, context.cacheDir.absolutePath))
            File(dir, "crash-${System.currentTimeMillis()}-$id.json").writeText(errorContent.toJSON())
            CrashReportStore.prune(context)
        } catch (e: Exception) {
            throw ReportSenderException("Unable to store crash report locally", e)
        }
    }

    private fun redactReport(errorContent: CrashReportData, roots: List<String>) {
        val rootRegexes = rootRegexes(roots)
        for ((key, value) in errorContent.toMap()) {
            if (value !is String) continue
            val sanitized = redact(value, rootRegexes)
            if (sanitized != value) errorContent.put(key, sanitized)
        }
    }

    internal companion object {
        private val CONTENT_URI = Regex("""content://\S+""")
        // Names can hold spaces, so a file path or an imported file's path is redacted to the end of its line.
        private val FILE_URI = Regex("""file://.*""")
        private val IMPORTS = Regex("""\S*imports/.*""")
        private val STORAGE_PATH = Regex("""(?<![\w])/(?:storage|sdcard|mnt|data|cache)/[^\r\n]*""")

        internal fun redact(text: String, root: String): String = redact(text, listOf(root))

        internal fun redact(text: String, roots: Collection<String>): String = redact(text, rootRegexes(roots))

        // A space stays in the path only when the next token looks like a file or directory.
        private fun rootRegexes(roots: Collection<String>): List<Regex> =
            roots.map { Regex(Pattern.quote(it) + """(?:[^\s]|\s(?=[^\s]*[./]))*""") }

        private fun redact(text: String, rootRegexes: List<Regex>): String =
            rootRegexes.fold(text) { acc, root -> acc.replace(root, "[redacted]") }
                .replace(CONTENT_URI, "[redacted]")
                .replace(FILE_URI, "[redacted]")
                .replace(IMPORTS, "[redacted]")
                .replace(STORAGE_PATH, "[redacted]")
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
