package io.github.pixelrick420.papyrus.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Opens a URI or fails with the same exception a missing file would raise. */
internal fun ContentResolver.openRequired(uri: Uri): InputStream =
    openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

@Suppress("TooGenericExceptionCaught")
internal fun <T> querySafely(default: T, query: () -> T): T = try {
    query()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    default
}

enum class SafAccess {
    Readable,
    NoAccess,
    Missing,
    Unreadable,
}

class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

class OpenPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

object SafStorage {
    data class Metadata(val displayName: String, val sizeBytes: Long, val mimeType: String?)

    // Call right after the pick, or the OS drops the grant on reboot.
    // Read-only: some providers refuse a write request outright.
    fun persistPermission(resolver: ContentResolver, uri: Uri): Boolean =
        take(resolver, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun take(resolver: ContentResolver, uri: Uri, flags: Int): Boolean = try {
        resolver.takePersistableUriPermission(uri, flags)
        true
    } catch (_: SecurityException) {
        false
    }

    /** The OS caps persisted grants per app, so release them when an entry is removed. */
    fun releasePermission(resolver: ContentResolver, uri: Uri) {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        var flags = 0
        if (held.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (held.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
        }
    }

    fun queryMetadata(context: Context, uri: Uri): Metadata {
        val resolver = context.contentResolver
        val found = querySafely<Pair<String?, Long>>(null to 0L) {
            var name: String? = null
            // Zero is kept as "unknown" when the provider omits a size; callers never treat it as an empty file.
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            name to size
        }
        return Metadata(found.first ?: uri.lastPathSegment ?: "document", found.second, queryMimeType(resolver, uri))
    }

    fun queryMimeType(resolver: ContentResolver, uri: Uri): String? =
        querySafely<String?>(null) { resolver.getType(uri) }

    // Opens the stream, as the receiving app will, so Readable means the URI is worth passing on; closes without reading.
    fun probeAccess(resolver: ContentResolver, uri: Uri): SafAccess = try {
        resolver.openInputStream(uri)?.use { SafAccess.Readable } ?: SafAccess.Missing
    } catch (_: SecurityException) {
        SafAccess.NoAccess
    } catch (_: FileNotFoundException) {
        // Before IOException, which it extends.
        SafAccess.Missing
    } catch (_: IOException) {
        SafAccess.Unreadable
    }
}
