package com.papyrus.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileNotFoundException
import java.io.IOException

/** Outcome of a readability check: a lost grant and a missing file need different messages. */
enum class SafAccess {
    Readable,
    /** Revoked, expired at reboot, or dropped by the provider. */
    NoAccess,
    /** Grant intact, document gone. */
    Missing,
    Unreadable,
}

/** Open-document picker that requests a persistable read grant. */
class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

/** Multi-select variant of [OpenPersistableDocument]. */
class OpenPersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

object SafStorage {
    data class Metadata(val displayName: String, val sizeBytes: Long, val mimeType: String?)

    /** Call right after the pick or the OS drops the grant on reboot. Read-only: some providers refuse a write request outright. */
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
        var name: String? = null
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        return Metadata(name ?: uri.lastPathSegment ?: "document", size, resolver.getType(uri))
    }

    /** Null when the provider reports no type or refuses the query, as a dropped grant can. */
    fun queryMimeType(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Opens the stream, which is the call the receiving app goes on to make, so Readable means the
     * URI is worth passing on. Closes without reading it: an open, not a copy.
     */
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
