package io.github.pixelrick420.papyrus.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.InputStream

/** Cache dir for decrypted PDF copies; swept on startup so a kill leaves no plaintext. */
const val PDF_UNLOCK_DIR = "pdf-unlock"

// A corrupt PDF reads as [NOT_ENCRYPTED] so it fails rather than prompts.
enum class PdfEncryption {
    NOT_ENCRYPTED,
    OWNER_ONLY,
    USER_PASSWORD;

    companion object {

        private const val MAX_SCAN_BYTES = 512L shl 20
        private const val SCAN_CHUNK = 64 * 1024
        private val ENCRYPT_TOKEN = "/Encrypt".toByteArray(Charsets.US_ASCII)

        // One allocation-free pass instead of a PdfBox load, which reads and parses the whole file; only a file mentioning `/Encrypt` goes to [authoritativeStatus].
        fun status(openStream: () -> InputStream): PdfEncryption =
            if (mentionsEncryption(openStream)) authoritativeStatus(openStream) else NOT_ENCRYPTED

        fun authoritativeStatus(openStream: () -> InputStream): PdfEncryption =
            try {
                openStream().use { stream ->
                    PDDocument.load(stream).use { document ->
                        if (document.isEncrypted) OWNER_ONLY else NOT_ENCRYPTED
                    }
                }
            } catch (e: InvalidPasswordException) {
                USER_PASSWORD
            } catch (e: Exception) {
                // Unreadable/malformed: not the "locked" case, so let the renderer fail generically.
                NOT_ENCRYPTED
            }

        // The `/Encrypt` trailer can sit at the end, near the start (linearized), or in a PDF 1.5 xref stream, so the whole stream is scanned in fixed chunks.
        internal fun mentionsEncryption(openStream: () -> InputStream, scanLimit: Long = MAX_SCAN_BYTES): Boolean =
            try {
                openStream().use { containsToken(it, scanLimit) }
            } catch (e: Exception) {
                false
            }

        private fun containsToken(stream: InputStream, scanLimit: Long): Boolean {
            val token = ENCRYPT_TOKEN
            val keep = token.size - 1
            val buffer = ByteArray(SCAN_CHUNK + keep)
            var carried = 0
            var scanned = 0L
            while (true) {
                val read = stream.read(buffer, carried, SCAN_CHUNK)
                if (read < 0) return false
                val filled = carried + read
                if (indexOf(buffer, filled, token) >= 0) return true
                scanned += read
                if (scanned > scanLimit) return true
                carried = minOf(keep, filled)
                System.arraycopy(buffer, filled - carried, buffer, 0, carried)
            }
        }

        private fun indexOf(buffer: ByteArray, length: Int, token: ByteArray): Int {
            val first = token[0]
            var i = 0
            val last = length - token.size
            while (i <= last) {
                if (buffer[i] == first) {
                    var j = 1
                    while (j < token.size && buffer[i + j] == token[j]) j++
                    if (j == token.size) return i
                }
                i++
            }
            return -1
        }

        fun decrypt(openStream: () -> InputStream, password: String, target: File) {
            try {
                openStream().use { stream ->
                    PDDocument.load(stream, password).use { document ->
                        document.setAllSecurityToBeRemoved(true)
                        document.save(target)
                    }
                }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }
}
