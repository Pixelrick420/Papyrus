package io.github.pixelrick420.papyrus.viewer

import java.io.File
import java.nio.file.Files

internal object CacheSweeper {

    fun sweepOlderThan(dir: File, cutoffMillis: Long) = sweepOlderThan(dir, cutoffMillis, null)

    fun sweepOlderThan(dir: File, cutoffMillis: Long, namePrefix: String?) {
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (namePrefix != null && !entry.name.startsWith(namePrefix)) continue
            if (Files.isSymbolicLink(entry.toPath())) continue
            if (entry.lastModified() >= cutoffMillis) continue
            runCatching { entry.deleteRecursively() }
        }
    }
}
