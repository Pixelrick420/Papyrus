package com.papyrus.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts

/** Open-document picker that requests a persistable read grant. */
class OpenPersistableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

/** ACTION_CREATE_DOCUMENT with persistable read/write grants, for the exported PDF target. */
class CreatePersistableDocument(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

object SafStorage {
    data class Metadata(val displayName: String, val sizeBytes: Long, val mimeType: String?)

    data class Access(val readable: Boolean, val writable: Boolean) {
        val canRead: Boolean get() = readable
        val lost: Boolean get() = !readable
    }

    /** A hint, not a verdict: an intent-opened document or a granted-tree child is readable with no persistedUriPermissions entry. */
    fun access(resolver: ContentResolver, uri: Uri): Access {
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return Access(readable = false, writable = false)
        return Access(held.isReadPermission, held.isWritePermission)
    }

    /** Call right after the pick or the OS drops the grant on reboot. Read only by default: some providers refuse a write request outright. */
    fun persistPermission(resolver: ContentResolver, uri: Uri, write: Boolean = false): Boolean {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (write && take(resolver, uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return true
        return take(resolver, uri, read)
    }

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
}
