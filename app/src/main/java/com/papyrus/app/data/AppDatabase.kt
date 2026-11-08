package com.papyrus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 1.0 starts a new database generation: 0.x databases are deleted before this one opens, never downgraded. */
@Database(
    entities = [
        DocumentEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        PdfOutlineEntity::class,
        DocumentTextEntity::class,
        DocumentTextFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    abstract fun readingProgressDao(): ReadingProgressDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun highlightDao(): HighlightDao

    abstract fun pdfOutlineDao(): PdfOutlineDao

    abstract fun searchDao(): SearchDao

    companion object {
        private const val DB_NAME = "papyrus-v1.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    LegacyDatabase.deleteIfPresent(context.applicationContext)
                    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                        .build()
                        .also { instance = it }
                }
            }
    }
}
