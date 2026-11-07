package com.papyrus.app.e2e

import androidx.test.core.app.ApplicationProvider
import com.papyrus.app.MainApplication

/**
 * What the content index holds right now, read from the database the running app shares with the test.
 *
 * The screen cannot tell which background pass wrote a document's text, only that it is there. The index can
 * be read while a viewer is still open, and the gated pass does not run then, so a row seen at that moment
 * was written by the eager one.
 */
internal object LibraryIndex {

    private val app get() = ApplicationProvider.getApplicationContext<MainApplication>()

    /** How many documents have a row of text in the index. */
    fun indexedCount(): Int = count("SELECT COUNT(*) FROM document_text")

    /** How many of those rows hold [word] in their text. */
    fun containing(word: String): Int = count("SELECT COUNT(*) FROM document_text WHERE text LIKE ?", "%$word%")

    private fun count(sql: String, vararg args: String): Int =
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
}
