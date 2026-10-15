package com.papyrus.app.data

import androidx.room.migration.Migration

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
 * The list is empty because no schema after [BASELINE_VERSION] has shipped.
 */
object Migrations {

    /** The oldest supported schema. Databases below it were test-only and are not migrated. */
    const val BASELINE_VERSION = 4

    /** The version in `@Database`; raise it together with a migration in [ALL]. */
    const val CURRENT_VERSION = 4

    /** Hand-written migrations, oldest first. Each must run from `startVersion` to `endVersion`. */
    val ALL: Array<Migration> = emptyArray()

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
