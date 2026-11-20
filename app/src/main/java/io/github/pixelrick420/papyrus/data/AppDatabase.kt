package io.github.pixelrick420.papyrus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        private val OUTLINE_SOURCE_MODIFIED = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pdf_outlines ADD COLUMN sourceModified INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    LegacyDatabase.deleteIfPresent(context.applicationContext)
                    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                        .addMigrations(OUTLINE_SOURCE_MODIFIED)
                        .build()
                        .also { instance = it }
                }
            }
    }
}
