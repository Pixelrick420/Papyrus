package io.github.pixelrick420.papyrus.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchTokenizerTest {

    @Test
    fun aDevanagariQueryDoesNotMatchScatteredFragments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val documents = database.documentDao()
            val search = database.searchDao()
            insert(documents, REAL_ID, "A real sentence with हिन्दी in it")
            insert(documents, DECOY_ID, "दी न है")
            index(search, REAL_ID, "A real sentence with हिन्दी in it")
            index(search, DECOY_ID, "दी न है")

            val hits = SearchRepository(search).search("हिन्दी")

            assertEquals(listOf(REAL_ID), hits.map { it.documentId })
        } finally {
            database.close()
        }
    }

    private suspend fun insert(documents: DocumentDao, id: Long, text: String) {
        documents.insertIgnore(
            DocumentEntity(
                id = id,
                uri = "content://test/$id",
                title = text,
                format = DocumentFormat.TEXT,
                sizeBytes = text.length.toLong(),
                createdAt = 0,
                lastOpenedAt = 0,
            ),
        )
    }

    private suspend fun index(search: SearchDao, id: Long, text: String) {
        search.replace(
            DocumentTextEntity(
                documentId = id,
                text = text,
                state = TextIndexState.INDEXED,
                charCount = text.length,
                sourceSize = text.length.toLong(),
                indexedAt = System.currentTimeMillis(),
                indexVersion = SearchIndexer.INDEX_VERSION,
            ),
        )
    }

    private companion object {
        const val REAL_ID = 1L
        const val DECOY_ID = 2L
    }
}
