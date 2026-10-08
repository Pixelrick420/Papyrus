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

/** The collective type for a multi-share: one type if every file resolves to it, so the chooser can narrow; the wildcard otherwise. */
internal fun sharedMimeType(types: List<String>): String {
    val first = types.firstOrNull() ?: return WILDCARD
    return if (types.all { it == first }) first else WILDCARD
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

/** Several files in one chooser; [uris] must not be empty. Same read-only guarantees as [shareIntent]. */
fun shareIntentMultiple(uris: List<Uri>, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(mimeType)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        // A ClipData of every URI, so the chooser sheet can name the files; built from the first item
        // because ClipData has no list constructor. EXTRA_STREAM stays for older receivers.
        .apply {
            val clip = ClipData.newRawUri("", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            setClipData(clip)
        }
        // SAF URIs share as-is (the row's persisted grant sub-grants); local copies go through the
        // FileProvider — see DocumentRepository.resolveShareUri.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)