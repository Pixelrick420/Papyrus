package io.github.pixelrick420.papyrus.data

import android.content.Context

/** Drops the pre-1.0 papyrus.db before the current papyrus-v1.db opens; that old data is not migrated. */
internal object LegacyDatabase {
    private const val NAME = "papyrus.db"

    fun deleteIfPresent(context: Context) {
        if (context.getDatabasePath(NAME).exists()) context.deleteDatabase(NAME)
    }
}
