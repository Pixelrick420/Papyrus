package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.InputStream

/** Cache dir for decrypted PDF copies; swept on startup so a kill leaves no plaintext. */
const val PDF_UNLOCK_DIR = "pdf-unlock"

/**
 * What opening a PDF will need. A corrupt PDF reads as [NOT_ENCRYPTED], so it fails generically
 * rather than prompting for a password.
 *
 * [OWNER_ONLY] opens on the empty password with no prompt, and counts as encrypted for the library and
 * autosave. [USER_PASSWORD] prompts.
 */
enum class PdfEncryption {
    NOT_ENCRYPTED,
    OWNER_ONLY,
    USER_PASSWORD;

    companion object {

        /** A scan that has read this many bytes without a verdict stops looking and defers to PdfBox. */
        private const val MAX_SCAN_BYTES = 512L shl 20
        private const val SCAN_CHUNK = 64 * 1024
        private val ENCRYPT_TOKEN = "/Encrypt".toByteArray(Charsets.US_ASCII)

        /**
         * The common case costs one allocation-free pass over the bytes instead of a PdfBox load, which
         * reads the whole file into memory and parses it: a file that never mentions `/Encrypt` cannot be
         * encrypted, so it is [NOT_ENCRYPTED] without a load. Only a file that does is handed to
         * [authoritativeStatus], so the three answers are exactly the ones PdfBox gives.
         */
        fun status(openStream: () -> InputStream): PdfEncryption =
            if (mentionsEncryption(openStream)) authoritativeStatus(openStream) else NOT_ENCRYPTED

        /** The PdfBox verdict, a full parse. [status] reaches it only after the byte screen finds `/Encrypt`; the viewer reaches it when the renderer asks for a password the screen did not expect. */
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

        /**
         * Whether the bytes contain the `/Encrypt` key at all. The key lives in a trailer dictionary, and
         * where that is varies: at the end of a classic file, near the *start* of a linearized one (its
         * first-page trailer), and inside the xref stream's dictionary in a PDF 1.5 file, which sits ahead
         * of a stream that can run to megabytes. A head window and a tail window can miss that last case, so
         * this reads the whole stream once, in fixed chunks, keeping only the few bytes a token split across
         * two chunks needs. A false positive (the token in page text) just costs the PdfBox check; a miss
         * cannot silently open an encrypted file, because the renderer then asks for a password and the
         * viewer falls back to [authoritativeStatus]. Past [scanLimit] bytes it gives up and says yes. An
         * unreadable stream says no, as [authoritativeStatus] does, so the renderer reports the real error.
         */
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

        /** Strips encryption into [target] for the renderer; any failure deletes the partial copy. */
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
