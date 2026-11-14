package io.github.pixelrick420.papyrus.viewer

import java.io.File

// Keeping a second deletes the first: a copy the viewer let go of would lie in cache in plaintext until the next start swept it. Unlocks run on IO threads, so each change is atomic.
internal class UnlockedCopy {
    private var heldFile: File? = null
    private var heldUri: String? = null

    val uri: String?
        @Synchronized get() = heldUri

    val isHeld: Boolean
        @Synchronized get() = heldFile != null

    @Synchronized
    fun keep(file: File, uri: String) {
        val replaced = heldFile
        heldFile = file
        heldUri = uri
        if (replaced != null && replaced != file) replaced.delete()
    }

    @Synchronized
    fun discard() {
        heldFile?.delete()
        heldFile = null
        heldUri = null
    }
}
