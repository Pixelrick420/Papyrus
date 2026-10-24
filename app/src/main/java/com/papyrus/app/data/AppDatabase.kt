package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 4 is the oldest supported schema; the number was not reset to 1 because installed devices
 * already hold v4 and Room throws on downgrade. No destructive fallback: wiping rows orphans app-owned files.
 */
@Database(
    entities = [
        DocumentEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        DocumentTextEntity::class,
        DocumentTextFtsEntity::class,
    ],
    version = Migrations.CURRENT_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun highlightDao(): HighlightDao

    abstract fun searchDao(): SearchDao

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
