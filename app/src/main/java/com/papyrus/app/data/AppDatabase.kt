package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 4,
    exportSchema = true, // written to app/schemas on every build and deliberately NOT tracked: the
    // migration tests execute this file's own SQL against sqlite-jdbc rather than diffing a
    // committed schema history, so a tracked export would only be a stale-identityHash trap.
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    companion object {
        private const val DB_NAME = "papyrus.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                    // No destructive fallback: the index is the user's document history. Add a Migration when bumping version.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
