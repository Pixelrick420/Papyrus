package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.InputStream

/** Cache dir for decrypted PDF copies; swept on startup so a kill leaves no plaintext. */
const val PDF_UNLOCK_DIR = "pdf-unlock"

/** A corrupt PDF reads as [NOT_ENCRYPTED], so it fails generically rather than prompting for a password. */
enum class PdfEncryption {
    NOT_ENCRYPTED,
    OWNER_ONLY,
    USER_PASSWORD;

    companion object {

        fun status(openStream: () -> InputStream): PdfEncryption =
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
