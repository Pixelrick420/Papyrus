package io.github.pixelrick420.papyrus.e2e

import androidx.test.core.app.ApplicationProvider
import io.github.pixelrick420.papyrus.MainApplication

internal object LibraryIndex {

    private val app get() = ApplicationProvider.getApplicationContext<MainApplication>()

    fun indexedCount(): Int = count("SELECT COUNT(*) FROM document_text")

    fun containing(word: String): Int = count("SELECT COUNT(*) FROM document_text WHERE text LIKE ?", "%$word%")

    private fun count(sql: String, vararg args: String): Int =
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
}
