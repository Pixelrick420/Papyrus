package com.papyrus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** A table rebuild, not ALTER TABLE DROP COLUMN: minSdk 26's SQLite predates 3.35, where DROP COLUMN landed. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DOCUMENTS_REBUILD_1_2.forEach(db::execSQL)
    }
}

/** Kept as data, not inline in migrate, so a JVM test runs the same SQL against a real SQLite file. */
internal val DOCUMENTS_REBUILD_1_2: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS documents_new (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        uri TEXT NOT NULL,
        title TEXT NOT NULL,
        mimeType TEXT,
        format TEXT NOT NULL,
        sizeBytes INTEGER NOT NULL,
        pageCount INTEGER NOT NULL,
        createdAt INTEGER NOT NULL,
        lastOpenedAt INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    INSERT INTO documents_new (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt)
    SELECT id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt
    FROM documents
    """.trimIndent(),
    "DROP TABLE documents",
    "ALTER TABLE documents_new RENAME TO documents",
    // Room expects the entity's declared indices to exist by name after a migration.
    "CREATE UNIQUE INDEX IF NOT EXISTS index_documents_uri ON documents (uri)",
    "CREATE INDEX IF NOT EXISTS index_documents_lastOpenedAt ON documents (lastOpenedAt)",
)

internal val DOCUMENTS_TABLE_V1: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS `documents` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `uri` TEXT NOT NULL,
        `title` TEXT NOT NULL,
        `mimeType` TEXT,
        `format` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `pageCount` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `lastOpenedAt` INTEGER NOT NULL,
        `sourceTreeUri` TEXT
    )
    """.trimIndent(),
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_documents_uri` ON `documents` (`uri`)",
    "CREATE INDEX IF NOT EXISTS `index_documents_lastOpenedAt` ON `documents` (`lastOpenedAt`)",
)
