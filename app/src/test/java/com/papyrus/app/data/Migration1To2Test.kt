package com.papyrus.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/** Runs MIGRATION_1_2 on a populated SQLite v1 table; a SQL snapshot misses data loss. */
class Migration1To2Test {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        DOCUMENTS_TABLE_V1.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun insertV1(
        id: Long,
        uri: String,
        title: String,
        mimeType: String?,
        format: String,
        size: Long,
        pages: Int,
        createdAt: Long,
        lastOpenedAt: Long,
        sourceTreeUri: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO documents
                (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt, sourceTreeUri)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.setString(2, uri)
            statement.setString(3, title)
            statement.setString(4, mimeType)
            statement.setString(5, format)
            statement.setLong(6, size)
            statement.setInt(7, pages)
            statement.setLong(8, createdAt)
            statement.setLong(9, lastOpenedAt)
            statement.setString(10, sourceTreeUri)
            statement.executeUpdate()
        }
    }

    private fun migrate() {
        DOCUMENTS_REBUILD_1_2.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun rows(query: String, onRow: (ResultSet) -> Unit): Int {
        var seen = 0
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rs ->
                while (rs.next()) {
                    onRow(rs)
                    seen++
                }
            }
        }
        return seen
    }

    private fun columnsOf(table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }

    private fun indexNames(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`documents`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }

    @Test
    fun `sourceTreeUri is gone and every other column is untouched`() {
        insertV1(1, "content://a", "A", "image/jpeg", "SCANNED", 10, 1, 100, 200, "content://tree")
        migrate()

        assertEquals(
            listOf("id", "uri", "title", "mimeType", "format", "sizeBytes", "pageCount", "createdAt", "lastOpenedAt"),
            columnsOf("documents"),
        )
    }

    @Test
    fun `rows survive the rebuild with every value intact`() {
        insertV1(1, "content://a", "Scan", "image/jpeg", "SCANNED", 2048, 3, 111, 999, "content://tree")
        insertV1(2, "content://b", "Note", null, "MARKDOWN", 0, 0, 222, 888, null)
        migrate()

        val seen = rows(
            "SELECT id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt FROM documents ORDER BY id",
        ) { rs ->
            if (rs.getLong("id") == 1L) {
                assertEquals("content://a", rs.getString("uri"))
                assertEquals("Scan", rs.getString("title"))
                assertEquals("image/jpeg", rs.getString("mimeType"))
                assertEquals("SCANNED", rs.getString("format"))
                assertEquals(2048L, rs.getLong("sizeBytes"))
                assertEquals(3, rs.getInt("pageCount"))
                assertEquals(111L, rs.getLong("createdAt"))
                assertEquals(999L, rs.getLong("lastOpenedAt"))
            } else {
                assertEquals("Note", rs.getString("title"))
                assertEquals("MARKDOWN", rs.getString("format"))
                // A null mimeType must stay null; coercing it to "" would send detection back to sniffing.
                assertNull(rs.getString("mimeType"))
            }
        }

        assertEquals("both rows should survive", 2, seen)
    }

    @Test
    fun `the autoincrement sequence continues past the highest id`() {
        insertV1(7, "content://a", "A", null, "SCANNED", 1, 1, 1, 1, null)
        migrate()

        connection.prepareStatement(
            "INSERT INTO documents (uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt) VALUES (?, 'C', NULL, 'SCANNED', 1, 1, 1, 1)",
        ).use { statement ->
            statement.setString(1, "content://c")
            statement.executeUpdate()
        }

        // A reused id would make the viewer open the wrong document after an insert.
        rows("SELECT id FROM documents ORDER BY id") { rs ->
            assertTrue("expected ids 7 and 8, saw ${rs.getLong("id")}", rs.getLong("id") in 7L..8L)
        }
        rows("SELECT MAX(id) FROM documents") { rs ->
            assertEquals(8L, rs.getLong(1))
        }
    }

    @Test
    fun `both declared indices exist again with the names Room looks for`() {
        migrate()

        // Room validates these by name at open, long after the migration reported success.
        assertEquals(
            setOf("index_documents_uri", "index_documents_lastOpenedAt"),
            indexNames().toSet(),
        )
    }

    @Test
    fun `the uri index is still unique so the same document cannot be indexed twice`() {
        insertV1(1, "content://a", "A", null, "SCANNED", 1, 1, 1, 1, null)
        migrate()

        // Re-importing a document goes through the unique index; a dropped index lists it twice.
        val failure = runCatching {
            connection.prepareStatement(
                "INSERT INTO documents (uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt) VALUES (?, 'dup', NULL, 'SCANNED', 1, 1, 1, 1)",
            ).use { statement ->
                statement.setString(1, "content://a")
                statement.executeUpdate()
            }
        }.exceptionOrNull()

        assertTrue("expected a uniqueness failure, got $failure", failure is SQLException)
        rows("SELECT COUNT(*) FROM documents") { rs -> assertEquals(1, rs.getInt(1)) }
    }

    @Test
    fun `an empty database migrates without error`() {
        // An install that indexed nothing must not hit a constraint failure or "no such table".
        migrate()

        rows("SELECT COUNT(*) FROM documents") { rs -> assertEquals(0, rs.getInt(1)) }
        assertTrue(columnsOf("documents").isNotEmpty())
    }
}
