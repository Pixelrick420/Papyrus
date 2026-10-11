package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Copies a handed-over document into filesDir/imports so it outlives the intent grant that delivered it. */
object LibraryImport {

    /** Where saved copies live; also the only path the FileProvider exposes (see file_paths.xml). */
    fun importsDir(context: Context): File = File(context.filesDir, IMPORTS_DIR)

    /** Streams [source] into filesDir/imports under a sanitized name and returns the new file. */
    suspend fun copyToImports(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, source)
        val dir = importsDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        val name = uniqueName(
            importFileName(meta.displayName, source.lastPathSegment, meta.mimeType, System.currentTimeMillis()),
            dir.list()?.toSet().orEmpty(),
        )
        copyStream({ openSource(context, source) }, File(dir, name))
    }

    /** Removes an app-owned copy; SAF content URIs and foreign file URIs are left alone. */
    fun deleteOwnedCopy(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val file = File(path)
        if (!isInside(importsDir(context), file)) return false
        return file.delete()
    }

    /** True when [file] lies directly in [dir] — canonical, so a sibling `imports-evil` never matches. */
    internal fun isInside(dir: File, file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == dir.canonicalFile
    }.getOrDefault(false)

    /** True when both files hold the same bytes; unequal sizes answer without reading either. */
    internal fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        return runCatching {
            a.inputStream().use { x -> b.inputStream().use { y -> streamsEqual(x, y) } }
        }.getOrDefault(false)
    }

    private fun streamsEqual(x: InputStream, y: InputStream): Boolean {
        val bufferX = ByteArray(COMPARE_BUFFER_SIZE)
        val bufferY = ByteArray(COMPARE_BUFFER_SIZE)
        while (true) {
            val readX = x.readFully(bufferX)
            val readY = y.readFully(bufferY)
            if (readX != readY) return false
            if (readX == 0) return true
            for (i in 0 until readX) if (bufferX[i] != bufferY[i]) return false
        }
    }

    /** Fills [buffer] unless the stream ends first; a single `read` may legitimately return less. */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun openSource(context: Context, source: Uri): InputStream =
        context.contentResolver.openInputStream(source) ?: throw FileNotFoundException(source.toString())

    /** Streams into a temp file beside [target], then renames it into place; a failure leaves nothing. */
    internal fun copyStream(openStream: () -> InputStream, target: File): File {
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, target.parentFile)
        var moved = false
        try {
            openStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            if (!temp.renameTo(target)) throw IOException("Couldn't move the imported file into place")
            moved = true
        } finally {
            if (!moved) temp.delete()
        }
        return target
    }

    /** On-disk name for a copy: display name, URI segment or timestamp, sanitized and given a MIME extension. */
    internal fun importFileName(
        displayName: String?,
        uriSegment: String?,
        mimeType: String?,
        timestamp: Long,
    ): String {
        val extension = extensionFor(mimeType)
        val base = listOf(displayName, uriSegment).firstNotNullOfOrNull(::sanitize)
            ?: "imported-$timestamp"
        return withExtension(base, extension)
    }

    /** A name nothing in [existing] holds: `report.pdf` becomes `report (1).pdf` on the first clash. */
    internal fun uniqueName(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var attempt = 1
        while (true) {
            val candidate = "$stem ($attempt)$extension"
            if (candidate !in existing) return candidate
            attempt++
        }
    }

    /** From the MIME type alone, via the same table format detection uses; null when unknown. */
    private fun extensionFor(mimeType: String?): String? =
        DocumentFormat.detect(null, mimeType).extensions.firstOrNull()

    /** Null for blank, separator-only or `.`/`..` names; trailing dots are not extensions. */
    private fun sanitize(name: String?): String? = name
        ?.replace('/', ' ')
        ?.replace('\\', ' ')
        ?.filter { it.code >= 0x20 }
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }

    /** Truncates the stem to a filesystem-safe length before deciding whether to append [extension]. */
    private fun withExtension(name: String, extension: String?): String {
        val stem = name.take(MAX_STEM_LENGTH).trimEnd('.')
        val hasExtension = stem.contains('.') && stem.substringAfterLast('.').isNotEmpty()
        if (hasExtension || extension == null) return stem
        return "$stem.$extension"
    }

    private const val IMPORTS_DIR = "imports"
    private const val TEMP_PREFIX = "import-"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_STEM_LENGTH = 120
    private const val COMPARE_BUFFER_SIZE = 64 * 1024
}
