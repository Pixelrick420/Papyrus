package io.github.pixelrick420.papyrus.data

import java.io.File
import java.io.InputStream

internal class SavedDuplicateFinder(
    private val rowsByTitle: suspend (String) -> List<DocumentEntity>,
    private val ownedCopy: (String) -> File?,
    private val openSource: (DocumentEntity) -> InputStream,
) {
    suspend fun find(source: DocumentEntity): DocumentEntity? {
        val candidates = rowsByTitle(source.title).mapNotNull { row ->
            ownedCopy(row.uri)?.takeIf { LibraryImport.matchesSize(it, source.sizeBytes) }?.let { row to it }
        }
        if (candidates.isEmpty()) return null
        val fingerprint = LibraryImport.fingerprint { openSource(source) } ?: return null
        return candidates.firstOrNull { (_, copy) -> LibraryImport.sameFingerprint(copy, fingerprint) }?.first
    }
}
