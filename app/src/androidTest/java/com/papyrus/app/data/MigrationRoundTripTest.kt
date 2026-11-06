package com.papyrus.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens every exported schema on a real SQLite and carries it to the current version. The JVM
 * `MigrationsTest` holds the statements to the exported JSON; this is the one that runs them, so a
 * migration that Room would reject on first open fails here instead of on a user's device.
 *
 * Works on its own database file, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class MigrationRoundTripTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val versions = Migrations.BASELINE_VERSION..Migrations.CURRENT_VERSION

    @Test
    fun everyExportedSchemaOpens() {
        for (version in versions) {
            helper.createDatabase("open-$version", version).close()
        }
    }

    @Test
    fun everyStepFromTheBaselineToTheCurrentVersionExists() {
        for (version in Migrations.BASELINE_VERSION until Migrations.CURRENT_VERSION) {
            assertTrue(
                "no migration starts at version $version",
                Migrations.ALL.any { it.startVersion == version },
            )
        }
    }

    @Test
    fun everyOldSchemaMigratesToTheCurrentOneAndRoomAcceptsTheResult() {
        for (version in Migrations.BASELINE_VERSION until Migrations.CURRENT_VERSION) {
            val name = "migrate-$version"
            helper.createDatabase(name, version).close()

            // validateDroppedTables: a migration that leaves a table behind is as wrong as a missing one.
            helper.runMigrationsAndValidate(name, Migrations.CURRENT_VERSION, true, *Migrations.ALL).close()
        }
    }

    @Test
    fun aLibraryFromTheBaselineKeepsItsRowsAndOpensInTheRealDatabase() {
        val name = "round-trip"
        helper.createDatabase(name, Migrations.BASELINE_VERSION).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, uri, title, format, sizeBytes, createdAt, lastOpenedAt) " +
                    "VALUES (7, 'content://test/7', 'Seven', 'PDF', 1234, 10, 20)",
            )
        }

        helper.runMigrationsAndValidate(name, Migrations.CURRENT_VERSION, true, *Migrations.ALL).close()

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .build()
        try {
            val document = runBlocking { database.documentDao().getById(7) }
            assertEquals("Seven", document?.title)
            assertEquals(1234L, document?.sizeBytes)
            // The column the migration added reads back as its default.
            assertEquals(false, document?.isStarred)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun aRowWrittenBeforeTheModifiedTimeColumnReadsAsNotRecorded() {
        val name = "modified-default"
        helper.createDatabase(name, 8).use { db ->
            db.execSQL(
                "INSERT INTO documents (id, uri, title, format, sizeBytes, createdAt, lastOpenedAt, isFavourite) " +
                    "VALUES (1, 'content://test/1', 'One', 'TEXT', 10, 0, 0, 0)",
            )
            db.execSQL(
                "INSERT INTO document_text (rowid, text, state, charCount, sourceSize, indexedAt, indexVersion) " +
                    "VALUES (1, 'hello', 'INDEXED', 5, 10, 0, 1)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(name, 9, true, *Migrations.ALL)

        migrated.query("SELECT sourceModified FROM document_text WHERE rowid = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        migrated.close()
    }
}
