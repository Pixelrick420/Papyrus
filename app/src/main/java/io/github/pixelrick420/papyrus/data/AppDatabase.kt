package io.github.pixelrick420.papyrus.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Current databases start at schema 1 and migrate in place to 3; 0.x data is not carried over. */
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
    version = 3,
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

        internal val OUTLINE_SOURCE_MODIFIED = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pdf_outlines ADD COLUMN sourceModified INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val TEXT_FAILURE_COUNT = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_text ADD COLUMN failureCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    LegacyDatabase.deleteIfPresent(context.applicationContext)
                    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                        .addMigrations(OUTLINE_SOURCE_MODIFIED, TEXT_FAILURE_COUNT)
                        .build()
                        .also { instance = it }
                }
            }
    }
}
