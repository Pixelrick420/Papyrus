package io.github.pixelrick420.papyrus

import android.content.Intent

/** VIEW and SEND both carry a document as a content:// URI; any other source or action is ignored. */
fun intentToDocumentUri(action: String?, data: String?, extraStream: String?): String? {
    val candidate = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> extraStream
        else -> null
    } ?: return null
    return candidate.takeIf { it.substringBefore(':').equals("content", ignoreCase = true) }
}
