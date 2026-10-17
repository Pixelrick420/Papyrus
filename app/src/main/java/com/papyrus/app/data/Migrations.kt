package com.papyrus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every schema migration of [AppDatabase]. 4 -> 5 is hand-written because the version-4 schema JSON
 *  was never exported, so Room could not auto-generate it. */
object Migrations {

    /** The oldest supported schema. Databases below it were test-only and are not migrated. */
    const val BASELINE_VERSION = 4

    /** The version in `@Database`; raise it together with a migration in [ALL]. */
    const val CURRENT_VERSION = 5

    /**
     * 4 -> 5. Every statement only adds, so existing rows keep their ids and the files under
     * `filesDir/imports` stay reachable; Room compares the result to its entities on first open.
     */
    internal val V4_TO_V5_SQL: List<String> = listOf(
        "ALTER TABLE `documents` ADD COLUMN `isFavourite` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `reading_progress` (" +
            "`documentId` INTEGER NOT NULL, " +
            "`locationKind` INTEGER NOT NULL, " +
            "`locationUnit` INTEGER NOT NULL, " +
            "`locationFraction` REAL NOT NULL, " +
            "`unitCount` INTEGER NOT NULL, " +
            "`zoomScale` REAL NOT NULL, " +
            "`progress` REAL NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`documentId`), " +
            "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`documentId` INTEGER NOT NULL, " +
            "`locationKind` INTEGER NOT NULL, " +
            "`locationUnit` INTEGER NOT NULL, " +
            "`locationFraction` REAL NOT NULL, " +
            "`label` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_bookmarks_documentId` ON `bookmarks` (`documentId`)",
    )

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V4_TO_V5_SQL.forEach { db.execSQL(it) }
        }
    }

    /** Hand-written migrations, oldest first. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_4_5)

    /**
     * Problems with [migrations] as a chain from [baseline] to [current], one message each, empty when
     * sound. A unit test holds the list to it, so a version bump without a migration cannot ship.
     */
    internal fun chainProblems(migrations: List<Migration>, baseline: Int, current: Int): List<String> {
        val problems = ArrayList<String>()
        val byStart = migrations.groupBy { it.startVersion }
        for ((start, same) in byStart) {
            if (same.size > 1) problems += "more than one migration starts at version $start"
        }
        for (migration in migrations) {
            if (migration.endVersion <= migration.startVersion) {
                problems += "migration ${migration.startVersion} -> ${migration.endVersion} does not move forward"
            }
        }
        var version = baseline
        val visited = HashSet<Migration>()
        while (version < current) {
            val step = byStart[version]?.firstOrNull()
            if (step == null) {
                problems += "no migration from version $version towards $current"
                break
            }
            if (step.endVersion <= version) break // already reported above; do not loop
            visited += step
            version = step.endVersion
        }
        if (version > current) problems += "the chain from $baseline overshoots $current (reaches $version)"
        for (migration in migrations) {
            if (migration !in visited && migration.startVersion >= baseline) {
                problems += "migration ${migration.startVersion} -> ${migration.endVersion} is not on the chain from $baseline to $current"
            }
        }
        return problems
    }
}
