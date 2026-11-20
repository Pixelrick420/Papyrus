package io.github.pixelrick420.papyrus.data

import android.content.Context

/** 1.0 moved to a new database file; deleting the 0.x one avoids a Room downgrade and leaves no dead storage. */
internal object LegacyDatabase {
    private const val NAME = "papyrus.db"

    fun deleteIfPresent(context: Context) {
        if (context.getDatabasePath(NAME).exists()) context.deleteDatabase(NAME)
    }
}
