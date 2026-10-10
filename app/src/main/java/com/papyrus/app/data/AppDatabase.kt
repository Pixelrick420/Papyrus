package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 4,
    // No schema is exported: only the current version is supported, so there is no
    // migration path and no schema history worth keeping.
    exportSchema = false,
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
                    // Only the current schema is supported, so a database on any other version fails to open.
                    .build()
                    .also { instance = it }
            }
    }
}
