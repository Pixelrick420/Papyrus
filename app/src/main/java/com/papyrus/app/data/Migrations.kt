package com.papyrus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration of [AppDatabase], in one place, plus the version numbers they are checked against.
 *
 * Prefer an explicit [Migration] object for anything non-trivial (a rename, a split or merge, a data
 * rewrite, a changed constraint): it is plain SQL a reviewer can read and a `MigrationTestHelper` can
 * run against the exported schema JSON. Use Room's `AutoMigration` only for a simple additive change,
 * such as a new nullable column or a new table, where the generated SQL is obviously what you would
 * have written. An `AutoMigration` still needs its `from` schema file exported, which is why `app/schemas/`
 * is committed.
 *
 * 4 -> 5 is hand-written rather than an `AutoMigration`: the version-4 schema JSON was never exported
 * (see [V4_TO_V5_SQL]), and an `AutoMigration` cannot be generated without it.
 */
object Migrations {

    /** The oldest supported schema. Databases below it were test-only and are not migrated. */
    const val BASELINE_VERSION = 4

    /** The version in `@Database`; raise it together with a migration in [ALL]. */
    const val CURRENT_VERSION = 5

    /**
     * 4 -> 5, the one additive step that carries reading position, bookmarks and favourites together:
     * unreleased work has no reason to publish intermediate versions.
     *
     * Every statement only adds. Nothing is dropped, rewritten or replaced, so existing rows keep their
     * ids, and the library files under `filesDir/imports` they point at stay reachable.
     *
     * The SQL is written to match what Room expects of the entities, because Room compares the
     * resulting tables to them (column names, types, nullability, defaults, foreign keys, indices) on
     * the first open and throws on any difference. Keep it in step with [DocumentEntity],
     * [ReadingProgressEntity] and [BookmarkEntity].
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

    /** Hand-written migrations, oldest first. Each must run from `startVersion` to `endVersion`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_4_5)

    /**
     * What is wrong with [migrations] as a chain from [baseline] to [current], one message per problem,
     * empty when it is sound. Kept apart from the list so a unit test can hold the list to it: a version
     * bump without a migration would otherwise be found only by a user with an old database.
     *
     * Sound means: from [baseline] there is exactly one migration to take at each step, none of them
     * goes backwards, and the walk lands exactly on [current]. Any migration the walk never visits is
     * reported too, since Room would silently never use it.
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
