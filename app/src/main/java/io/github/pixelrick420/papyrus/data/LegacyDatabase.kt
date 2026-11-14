package io.github.pixelrick420.papyrus.data

import android.content.Context
import java.io.File

/** 1.0 moved to a new database file; deleting the 0.x one avoids a Room downgrade and leaves no dead storage. */
internal object LegacyDatabase {
    private const val NAME = "papyrus.db"
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    fun deleteIfPresent(context: Context) {
        val database = context.getDatabasePath(NAME)
        if (!database.exists()) return
        context.deleteDatabase(NAME)
        SIDECARS.forEach { File(database.path + it).delete() }
    }
}
