package com.papyrus.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Runs DOCUMENTS_REBUILD_3_4 against a populated SQLite v3 database. What Room checks afterwards is
 * the table shape and the index set, so both are pinned here along with the data.
 */
class Migration3To4Test {

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun tearDown() {
        connection.close()
    }

    private fun exec(statements: List<String>) {
        statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
    }

    /** The v3 shape a real install has: the v1 -> v2 rebuild, then scan_pages dropped. */
    private fun givenV3() {
        exec(DOCUMENTS_TABLE_V1)
        exec(DOCUMENTS_REBUILD_1_2)
        exec(DROP_SCAN_PAGES)
        // Ids 1 and 7, not 1 and 2: a migration that renumbered rows would show up as a gap closing.
        connection.createStatement().use {
            it.execute(
                """
                INSERT INTO documents
                    (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt)
                VALUES (1, 'content://a', 'Report', 'application/pdf', 'PDF', 2048, 3, 111, 999)
                """.trimIndent(),
            )
            it.execute(
                """
                INSERT INTO documents
                    (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt)
                VALUES (7, 'content://b', 'Note', NULL, 'MARKDOWN', 0, 0, 222, 888)
                """.trimIndent(),
            )
        }
    }

    private fun migrate() = exec(DOCUMENTS_REBUILD_3_4)

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

    /** `name:TYPE:notnull:pk` per column, in table order. */
    private fun columns(): List<String> = buildList {
        rows("PRAGMA table_info(`documents`)") { rs ->
            add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getInt("pk")}")
        }
    }

    /** Index name to whether it is unique. */
    private fun indices(): Map<String, Boolean> = buildMap {
        rows("PRAGMA index_list(`documents`)") { rs -> put(rs.getString("name"), rs.getInt("unique") == 1) }
    }

    private fun tableExists(table: String): Boolean =
        rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'") { } > 0

    @Test
    fun `the table has exactly the columns the entity declares`() {
        givenV3()

        migrate()

        assertEquals(
            listOf(
                "id:INTEGER:1:1",
                "uri:TEXT:1:0",
                "title:TEXT:1:0",
                "format:TEXT:1:0",
                "sizeBytes:INTEGER:1:0",
                "createdAt:INTEGER:1:0",
                "lastOpenedAt:INTEGER:1:0",
            ),
            columns(),
        )
    }

    @Test
    fun `document rows survive with their ids and every kept value intact`() {
        givenV3()

        migrate()

        val seen = rows("SELECT * FROM documents ORDER BY id") { rs ->
            when (rs.getLong("id")) {
                1L -> {
                    assertEquals("content://a", rs.getString("uri"))
                    assertEquals("Report", rs.getString("title"))
                    assertEquals("PDF", rs.getString("format"))
                    assertEquals(2048L, rs.getLong("sizeBytes"))
                    assertEquals(111L, rs.getLong("createdAt"))
                    assertEquals(999L, rs.getLong("lastOpenedAt"))
                }

                7L -> {
                    assertEquals("content://b", rs.getString("uri"))
                    assertEquals("Note", rs.getString("title"))
                    assertEquals("MARKDOWN", rs.getString("format"))
                    assertEquals(222L, rs.getLong("createdAt"))
                    assertEquals(888L, rs.getLong("lastOpenedAt"))
                }

                else -> throw AssertionError("unexpected id ${rs.getLong("id")}")
            }
        }
        assertEquals(2, seen)
    }

    @Test
    fun `only the unique uri index is left, and the lastOpenedAt index is gone`() {
        givenV3()
        assertTrue(
            "precondition: v3 carries the lastOpenedAt index",
            indices().containsKey("index_documents_lastOpenedAt"),
        )

        migrate()

        // Room compares the index set by name after a migration, so a leftover would fail the open.
        assertEquals(mapOf("index_documents_uri" to true), indices())
    }

    @Test
    fun `the same document still cannot be indexed twice`() {
        givenV3()
        migrate()

        val failure = runCatching {
            connection.prepareStatement(
                "INSERT INTO documents (uri, title, format, sizeBytes, createdAt, lastOpenedAt) VALUES (?, 'dup', 'PDF', 1, 1, 1)",
            ).use { statement ->
                statement.setString(1, "content://a")
                statement.executeUpdate()
            }
        }.exceptionOrNull()

        assertTrue("expected a uniqueness failure, got $failure", failure is SQLException)
        rows("SELECT COUNT(*) FROM documents") { rs -> assertEquals(2, rs.getInt(1)) }
    }

    @Test
    fun `new rows keep getting fresh ids after the rebuild`() {
        givenV3()
        migrate()

        connection.createStatement().use {
            it.execute("INSERT INTO documents (uri, title, format, sizeBytes, createdAt, lastOpenedAt) VALUES ('content://c', 'C', 'TEXT', 1, 1, 1)")
        }

        rows("SELECT id FROM documents WHERE uri = 'content://c'") { rs -> assertEquals(8L, rs.getLong("id")) }
        rows("SELECT sql FROM sqlite_master WHERE name = 'documents'") { rs ->
            assertTrue("AUTOINCREMENT must survive the rebuild", rs.getString("sql").contains("AUTOINCREMENT"))
        }
    }

    @Test
    fun `no scratch table is left behind`() {
        givenV3()

        migrate()

        assertFalse(tableExists("documents_new"))
        assertTrue(tableExists("documents"))
    }

    @Test
    fun `an empty database migrates without error`() {
        exec(DOCUMENTS_TABLE_V1)
        exec(DOCUMENTS_REBUILD_1_2)
        exec(DROP_SCAN_PAGES)

        migrate()

        rows("SELECT COUNT(*) FROM documents") { rs -> assertEquals(0, rs.getInt(1)) }
        assertEquals(7, columns().size)
    }

    @Test
    fun `a v1 install walks every step to v4 with its rows intact`() {
        exec(DOCUMENTS_TABLE_V1)
        connection.createStatement().use {
            it.execute(
                """
                INSERT INTO documents
                    (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt, sourceTreeUri)
                VALUES (4, 'content://v1', 'Old', 'application/pdf', 'PDF', 10, 2, 5, 6, 'content://tree')
                """.trimIndent(),
            )
        }

        exec(DOCUMENTS_REBUILD_1_2)
        exec(DROP_SCAN_PAGES)
        migrate()

        assertEquals(
            1,
            rows("SELECT * FROM documents") { rs ->
                assertEquals(4L, rs.getLong("id"))
                assertEquals("content://v1", rs.getString("uri"))
                assertEquals("Old", rs.getString("title"))
                assertEquals(10L, rs.getLong("sizeBytes"))
                assertEquals(6L, rs.getLong("lastOpenedAt"))
            },
        )
        assertEquals(mapOf("index_documents_uri" to true), indices())
    }
}
