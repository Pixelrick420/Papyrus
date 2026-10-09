package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 4,
    exportSchema = true, // written to app/schemas on every build and deliberately NOT tracked: the
    // export is a build artifact, not a schema history, so committing it would only be a
    // stale-identityHash trap.
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
