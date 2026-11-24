package io.github.pixelrick420.papyrus.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"
    private lateinit var helper: SupportSQLiteOpenHelper

    @After
    fun tearDown() {
        if (::helper.isInitialized) helper.close()
        context.deleteDatabase(name)
    }

    @Test
    fun versionTwoTextRowsGainAZeroFailureCount() {
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V2_DOCUMENT_TEXT)
                        db.execSQL("INSERT INTO document_text VALUES (1, 'kept', 'INDEXED', 4, 10, 100, 1, 50)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase

        AppDatabase.TEXT_FAILURE_COUNT.migrate(db)

        db.query("SELECT text, failureCount FROM document_text WHERE rowid = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    private companion object {
        const val V2_DOCUMENT_TEXT = "CREATE TABLE document_text (" +
            "rowid INTEGER PRIMARY KEY, text TEXT NOT NULL, state TEXT NOT NULL, " +
            "charCount INTEGER NOT NULL, sourceSize INTEGER NOT NULL, indexedAt INTEGER NOT NULL, " +
            "indexVersion INTEGER NOT NULL, sourceModified INTEGER NOT NULL DEFAULT 0)"
    }
}
