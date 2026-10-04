package com.papyrus.app.data

import android.content.ClipData
import android.content.Intent
import android.net.Uri

private const val WILDCARD = "*/*"
private const val TEXT_PLAIN = "text/plain"

/** Placeholder answers that say nothing about the file. */
private val GENERIC_TYPES = setOf("application/octet-stream", "application/binary", WILDCARD)

/**
 * The provider's own type, unless it is a placeholder or a plain-text mislabel. Falls back to the
 * detected format's type, then to the wildcard, so a document with no usable type can still be shared.
 */
internal fun shareMimeType(document: DocumentEntity): String {
    val declared = document.format.mimeTypes.firstOrNull()
    val reported = document.mimeType?.trim()?.takeIf { it.isNotEmpty() }
    if (reported == null || reported in GENERIC_TYPES) return declared ?: WILDCARD
    // Right for TXT and CODE, a mislabel for every other format.
    if (reported == TEXT_PLAIN && declared != null && declared != TEXT_PLAIN) return declared
    // Anything else specific is the provider being authoritative about its own file.
    return reported
}

/** Read-only by construction: no target can write back, rename, or delete the user's own document. */
fun shareIntent(uri: Uri, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        // createChooser reflects this ClipData into the chooser intent, and EXTRA_STREAM alone
        // leaves it empty, so the sheet cannot read the document's name. EXTRA_STREAM stays for
        // receivers that predate ClipData. setClipData returns void, so it cannot be chained.
        .apply { setClipData(ClipData.newRawUri("", uri)) }
        // The document's own SAF URI, not a copy behind a FileProvider: every library row holds a
        // persisted grant from register(), so this asks for a sub-grant of access the user gave.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)