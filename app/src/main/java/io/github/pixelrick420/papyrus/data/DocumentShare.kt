package io.github.pixelrick420.papyrus.data

import android.content.ClipData
import android.content.Intent
import android.net.Uri

private const val WILDCARD = "*/*"
private const val TEXT_PLAIN = "text/plain"

private val GENERIC_TYPES = setOf("application/octet-stream", "application/binary", WILDCARD)

internal fun shareMimeType(format: DocumentFormat, reportedMimeType: String?): String {
    val declared = format.mimeTypes.firstOrNull()
    val reported = reportedMimeType?.trim()?.takeIf { it.isNotEmpty() }
    if (reported == null || reported in GENERIC_TYPES) return declared ?: WILDCARD
    // Right for TXT and CODE, a mislabel for every other format.
    if (reported == TEXT_PLAIN && declared != null && declared != TEXT_PLAIN) return declared
    return reported
}

internal fun sharedMimeType(types: List<String>): String {
    val first = types.firstOrNull() ?: return WILDCARD
    return if (types.all { it == first }) first else WILDCARD
}

/** Read-only by construction: no target can write back, rename, or delete the user's own document. */
fun shareIntent(uri: Uri, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        // createChooser reflects this ClipData so the sheet can read the name; EXTRA_STREAM stays for receivers that predate ClipData.
        .apply { setClipData(ClipData.newRawUri("", uri)) }
        // SAF URIs share as-is; local copies go through the FileProvider.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

fun shareIntentMultiple(uris: List<Uri>, mimeType: String): Intent =
    Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(mimeType)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        // Built from the first item because ClipData has no list constructor.
        .apply {
            val clip = ClipData.newRawUri("", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            setClipData(clip)
        }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
