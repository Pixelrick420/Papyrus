package io.github.pixelrick420.papyrus.viewer

import java.util.concurrent.ConcurrentHashMap

internal data class PdfEncryptionVerdictKey(
    val uri: String,
    val sizeBytes: Long,
    val modified: Long,
)

// The verdict is stable until the source changes, so repeated opens reuse it instead of rescanning the file.
internal class PdfEncryptionVerdicts(
    private val maxEntries: Int = 8,
    private val scan: (PdfEncryptionVerdictKey) -> PdfEncryption,
) {
    private val known = ConcurrentHashMap<PdfEncryptionVerdictKey, PdfEncryption>()

    fun of(key: PdfEncryptionVerdictKey): PdfEncryption = known[key] ?: scan(key).also { verdict ->
        if (known.size >= maxEntries && !known.containsKey(key)) known.clear()
        known[key] = verdict
    }

    fun clear() = known.clear()
}
