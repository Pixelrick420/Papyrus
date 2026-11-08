package com.papyrus.app.viewer

import java.io.File

/**
 * The decrypted copy of a password-protected document that a viewer owns, one at a time. Keeping a second
 * deletes the first: the viewer holds a single reference, so a copy it let go of would lie in cache, in
 * plaintext, until the next start swept it. Unlocks run on IO threads and can finish together, so each
 * change is atomic.
 */
internal class UnlockedCopy {
    private var heldFile: File? = null
    private var heldUri: String? = null

    /** The copy's URI, which the viewer reads in place of the document's own. */
    val uri: String?
        @Synchronized get() = heldUri

    val isHeld: Boolean
        @Synchronized get() = heldFile != null

    /** Keeps [file] as the copy, and deletes the one it replaces. */
    @Synchronized
    fun keep(file: File, uri: String) {
        val replaced = heldFile
        heldFile = file
        heldUri = uri
        if (replaced != null && replaced != file) replaced.delete()
    }

    /** Deletes the copy, if there is one, and forgets it. */
    @Synchronized
    fun discard() {
        heldFile?.delete()
        heldFile = null
        heldUri = null
    }
}
