package com.papyrus.app.data

import android.content.ClipData
import android.content.Intent
import android.net.Uri

private const val WILDCARD = "*/*"
private const val TEXT_PLAIN = "text/plain"

/** Placeholder answers that say nothing about the file. */
private val GENERIC_TYPES = setOf("application/octet-stream", "application/binary", WILDCARD)

/** The provider's own type, unless a placeholder or a plain-text mislabel; falls back to the detected format's type, then the wildcard. */
internal fun shareMimeType(format: DocumentFormat, reportedMimeType: String?): String {
    val declared = format.mimeTypes.firstOrNull()
    val reported = reportedMimeType?.trim()?.takeIf { it.isNotEmpty() }
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
        // createChooser reflects this ClipData into the chooser, so the sheet can read the document's
        // name; EXTRA_STREAM stays for receivers that predate ClipData. setClipData returns void.
        .apply { setClipData(ClipData.newRawUri("", uri)) }
        // SAF URIs share as-is (the row's persisted grant sub-grants); local copies go through the
        // FileProvider — see DocumentRepository.resolveShareUri.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)