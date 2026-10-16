package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The one Room database: the `documents` library plus the tables hanging off it ([ReadingProgressEntity],
 * [BookmarkEntity]), each removed with its document by `ON DELETE CASCADE`.
 *
 * **Schema versions.** Version 4 is the oldest schema that is supported: earlier ones were test-only
 * and have no migration path. The number was deliberately not reset to 1, because devices already
 * hold a version-4 database and Room throws on a downgrade. Every later schema change therefore adds
 * a `Migration` for it to [Migrations.ALL], starting with 4 -> 5, and bumps [Migrations.CURRENT_VERSION].
 * The schema of each released version is exported to `app/schemas/` and committed, so a migration can
 * be written and tested against the exact JSON the shipped database was built from.
 *
 * There is intentionally no `fallbackToDestructiveMigration`, in any build type: a row points at an
 * app-owned copy under `filesDir/imports`, so wiping rows on a version mismatch would orphan those
 * files for good. A missing migration is a crash on launch in development, which is the right place
 * to find out.
 */
@Database(
    entities = [DocumentEntity::class, ReadingProgressEntity::class, BookmarkEntity::class],
    version = Migrations.CURRENT_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    abstract fun readingProgressDao(): ReadingProgressDao

    companion object {
        private const val DB_NAME = "papyrus.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    .addMigrations(*Migrations.ALL)
                    .build()
                    .also { instance = it }
            }
    }
}
