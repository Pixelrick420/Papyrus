package io.github.pixelrick420.papyrus.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.InputStream

// A corrupt PDF reads as [NOT_ENCRYPTED] so it fails rather than prompts.
enum class PdfEncryption {
    NOT_ENCRYPTED,
    OWNER_ONLY,
    USER_PASSWORD;

    companion object {

        private const val MAX_SCAN_BYTES = 512L shl 20
        private const val SCAN_CHUNK = 64 * 1024
        private const val MAX_REPLAY_BYTES = 8L * 1024 * 1024
        private val ENCRYPT_TOKEN = "/Encrypt".toByteArray(Charsets.US_ASCII)

        // One scan instead of a PdfBox load for a plain file; a file mentioning `/Encrypt` is replayed from the same descriptor when it fits the small replay buffer.
        @Suppress("TooGenericExceptionCaught")
        fun status(openStream: () -> InputStream): PdfEncryption = try {
            val source = try {
                openStream()
            } catch (_: Exception) {
                return NOT_ENCRYPTED
            }
            ReplayInputStream(source, MAX_REPLAY_BYTES).use { stream ->
                when {
                    !mentionsEncryption(stream) -> NOT_ENCRYPTED
                    stream.rewind() -> authoritativeStatus(stream)
                    // A large scan cannot be replayed in memory, so the parser gets a fresh descriptor.
                    else -> authoritativeStatus(openStream)
                }
            }
        } catch (_: OutOfMemoryError) {
            NOT_ENCRYPTED
        } catch (_: Throwable) {
            NOT_ENCRYPTED
        }

        fun authoritativeStatus(openStream: () -> InputStream): PdfEncryption =
            openStream().use(::authoritativeStatus)

        // A bad file is classified as unencrypted here so the renderer reports the real damage.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun authoritativeStatus(stream: InputStream): PdfEncryption =
            try {
                PDDocument.load(stream).use { document ->
                    if (document.isEncrypted) OWNER_ONLY else NOT_ENCRYPTED
                }
            } catch (e: InvalidPasswordException) {
                USER_PASSWORD
            } catch (e: Exception) {
                // Unreadable/malformed: not the "locked" case, so let the renderer fail generically.
                NOT_ENCRYPTED
            } catch (e: OutOfMemoryError) {
                // A whole-file load can exhaust the heap; that is not evidence of a locked file.
                NOT_ENCRYPTED
            }

        // The `/Encrypt` trailer can sit at the end, near the start (linearized), or in a PDF 1.5 xref stream, so the whole stream is scanned in fixed chunks.
        // A failed scan is not evidence of encryption; the renderer still opens the file.
        internal fun mentionsEncryption(openStream: () -> InputStream, scanLimit: Long = MAX_SCAN_BYTES): Boolean =
            openStream().use { mentionsEncryption(it, scanLimit) }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        internal fun mentionsEncryption(stream: InputStream, scanLimit: Long = MAX_SCAN_BYTES): Boolean =
            try {
                containsToken(stream, scanLimit)
            } catch (_: Exception) {
                false
            } catch (_: Throwable) {
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

        // A failed save, including one that exhausts the heap, must not leave plaintext in the cache.
        fun decrypt(openStream: () -> InputStream, password: String, target: File) {
            var saved = false
            try {
                openStream().use { stream ->
                    PDDocument.load(stream, password).use { document ->
                        document.setAllSecurityToBeRemoved(true)
                        document.save(target)
                    }
                }
                saved = true
            } finally {
                if (!saved) target.delete()
            }
        }
    }
}

// Keeps the scanned prefix so a short PDF can be handed to PdfBox without opening the source twice.
private class ReplayInputStream(
    private val source: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private val recorded = java.io.ByteArrayOutputStream()
    private var replay: java.io.ByteArrayInputStream? = null
    private var totalRead = 0L

    fun rewind(): Boolean {
        if (totalRead > maxBytes) return false
        replay = java.io.ByteArrayInputStream(recorded.toByteArray())
        return true
    }

    override fun read(): Int {
        replay?.let { bytes ->
            val value = bytes.read()
            if (value >= 0) return value
            replay = null
        }
        val value = source.read()
        if (value >= 0) record(value)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        replay?.let { buffer ->
            val count = buffer.read(bytes, offset, length)
            if (count >= 0) return count
            replay = null
        }
        val count = source.read(bytes, offset, length)
        if (count > 0) record(bytes, offset, count)
        return count
    }

    private fun record(value: Int) {
        if (totalRead < maxBytes) recorded.write(value)
        totalRead++
    }

    private fun record(bytes: ByteArray, offset: Int, length: Int) {
        val remaining = maxBytes - totalRead
        if (remaining > 0) {
            val keep = minOf(length.toLong(), remaining).toInt()
            recorded.write(bytes, offset, keep)
        }
        totalRead += length
    }

    override fun close() {
        source.close()
    }
}
