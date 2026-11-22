package io.github.pixelrick420.papyrus.data

import android.content.Context

/** Drops the 0.x database before the current one opens; that old data is not migrated. */
internal object LegacyDatabase {
    private const val NAME = "papyrus.db"

    fun deleteIfPresent(context: Context) {
        if (context.getDatabasePath(NAME).exists()) context.deleteDatabase(NAME)
    }
}
