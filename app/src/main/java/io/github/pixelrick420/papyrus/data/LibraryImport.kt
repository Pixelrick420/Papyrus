package io.github.pixelrick420.papyrus.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private val KNOWN_EXTENSIONS: Set<String> = DocumentFormat.entries.flatMap { it.extensions }.toSet()

/** Copies a document into filesDir/imports so it outlives the intent grant that delivered it. */
object LibraryImport {

    /** Where saved copies live; also the only path the FileProvider exposes (see file_paths.xml). */
    fun importsDir(context: Context): File = File(context.filesDir, IMPORTS_DIR)

    suspend fun copyToImports(context: Context, source: Uri): File = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, source)
        val dir = importsDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        val target = reserveTarget(
            dir,
            importFileName(meta.displayName, source.lastPathSegment, meta.mimeType, System.currentTimeMillis()),
        )
        copyStream({ openSource(context, source) }, target)
    }

    // Taking the path before the copy keeps two concurrent saves from choosing the same name.
    private fun reserveTarget(dir: File, name: String): File {
        val taken = HashSet<String>()
        var candidate = name
        repeat(MAX_NAME_ATTEMPTS) {
            val file = File(dir, candidate)
            if (file.createNewFile()) return file
            taken += candidate
            candidate = uniqueName(name, taken)
        }
        val fallback = File(dir, withUuid(candidate))
        if (!fallback.createNewFile()) throw IOException("Couldn't reserve an import file name")
        return fallback
    }

    internal fun withUuid(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)}-${UUID.randomUUID()}${name.substring(dot)}"
        } else {
            "$name-${UUID.randomUUID()}"
        }
    }

    fun deleteOwnedCopy(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val file = File(path)
        if (!isInside(importsDir(context), file)) return false
        return file.delete()
    }

    // The age bound keeps it off a copy still being saved, which exists a moment before its row; a file with no readable time is kept.
    internal fun sweepOrphans(dir: File, referenced: Set<File>, modifiedBefore: Long): Int {
        val kept = referenced.mapNotNullTo(HashSet()) { runCatching { it.canonicalFile }.getOrNull() }
        var removed = 0
        for (file in dir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val modified = file.lastModified()
            if (modified == 0L || modified >= modifiedBefore) continue
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: continue
            if (canonical !in kept && file.delete()) removed++
        }
        return removed
    }

    /** True when [file] lies in [dir] - canonical, so a sibling `imports-evil` never matches. */
    internal fun isInside(dir: File, file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == dir.canonicalFile
    }.getOrDefault(false)

    internal data class Fingerprint(val size: Long, val hash: String)

    // One pass over the source serves every same-title candidate.
    internal fun fingerprint(openSource: () -> InputStream): Fingerprint? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        openSource().use { input ->
            val buffer = ByteArray(COMPARE_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        val bytes = digest.digest()
        val hash = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            hash.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
        }
        Fingerprint(size, hash.toString())
    }.getOrNull()

    internal fun sameFingerprint(candidate: File, source: Fingerprint): Boolean = runCatching {
        fingerprint { candidate.inputStream() } == source
    }.getOrDefault(false)

    // A size the provider did not report (<= 0) cannot rule a candidate out.
    internal fun matchesSize(candidate: File, sourceSize: Long): Boolean =
        sourceSize <= 0 || candidate.length() == sourceSize

    private fun openSource(context: Context, source: Uri): InputStream = context.contentResolver.openRequired(source)

    internal fun copyStream(openStream: () -> InputStream, target: File): File {
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, target.parentFile)
        var moved = false
        try {
            openStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                    output.channel.force(true)
                }
            }
            if (!temp.renameTo(target)) throw IOException("Couldn't move the imported file into place")
            moved = true
        } finally {
            if (!moved) {
                temp.delete()
                target.delete()
            }
        }
        return target
    }

    fun importFileName(
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

    private fun extensionFor(mimeType: String?): String? =
        DocumentFormat.detect(null, mimeType).extensions.firstOrNull()

    private fun sanitize(name: String?): String? = name
        ?.replace('/', ' ')
        ?.replace('\\', ' ')
        ?.filter { it.code >= 0x20 }
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf { it.isNotBlank() }

    internal fun withExtension(name: String, extension: String?): String {
        val dot = name.lastIndexOf('.')
        val knownSuffix = dot > 0 && name.substring(dot + 1).lowercase() in KNOWN_EXTENSIONS
        val (stem, suffix) = when {
            extension == null -> name to null
            name.endsWith(".$extension", ignoreCase = true) ->
                name.dropLast(extension.length + 1) to name.takeLast(extension.length + 1)
            knownSuffix -> name.substring(0, dot) to name.substring(dot)
            else -> name to ".$extension"
        }
        return truncateStem(stem) + suffix.orEmpty()
    }

    // Cutting by code point keeps surrogate pairs intact; the byte cap leaves room for suffixes and the uuid fallback.
    private fun truncateStem(stem: String): String {
        val cut = StringBuilder()
        var units = 0
        var bytes = 0
        var index = 0
        while (index < stem.length) {
            val codePoint = stem.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val codePointBytes = utf8Length(codePoint)
            if (units + charCount > MAX_STEM_LENGTH || bytes + codePointBytes > MAX_STEM_BYTES) break
            cut.appendCodePoint(codePoint)
            units += charCount
            bytes += codePointBytes
            index += charCount
        }
        return cut.toString().trimEnd(' ', '.').ifEmpty { FALLBACK_STEM }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }

    private const val IMPORTS_DIR = "imports"
    private const val TEMP_PREFIX = "import-"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_STEM_LENGTH = 120
    private const val MAX_STEM_BYTES = 200
    private const val FALLBACK_STEM = "document"
    private const val MAX_NAME_ATTEMPTS = 64
    private const val COMPARE_BUFFER_SIZE = 64 * 1024
}
