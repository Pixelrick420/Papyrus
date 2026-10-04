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

/**
 * Runs DROP_SCAN_PAGES against a real SQLite v2 database. Two upgrade paths matter: a v2 install
 * that has the table, and a v1 install stepping straight to v3, where the v1 -> v2 rebuild never
 * created scan_pages and the DROP must tolerate its absence.
 */
class Migration2To3Test {

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

    /** Builds the v2 shape a real install would have: documents via the v1 -> v2 rebuild, plus scan_pages. */
    private fun givenV2WithScans() {
        exec(DOCUMENTS_TABLE_V1)
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
                VALUES (2, 'content://b', 'Note', NULL, 'MARKDOWN', 0, 0, 222, 888)
                """.trimIndent(),
            )
        }
        exec(DOCUMENTS_REBUILD_1_2)
        exec(SCAN_PAGES_TABLE_V2)
        connection.createStatement().use {
            it.execute("INSERT INTO scan_pages (documentId, position, filter) VALUES (1, 0, 'COLOR')")
            it.execute("INSERT INTO scan_pages (documentId, position, filter) VALUES (1, 1, 'GRAYSCALE')")
            it.execute("INSERT INTO scan_pages (documentId, position, filter) VALUES (2, 0, 'B_W')")
        }
    }

    /** Builds v1 and stops there, the state a device that predates the scan_pages table is in. */
    private fun givenV1WithoutScans() {
        exec(DOCUMENTS_TABLE_V1)
        connection.createStatement().use {
            it.execute(
                """
                INSERT INTO documents
                    (id, uri, title, mimeType, format, sizeBytes, pageCount, createdAt, lastOpenedAt)
                VALUES (1, 'content://a', 'Report', 'application/pdf', 'PDF', 2048, 3, 111, 999)
                """.trimIndent(),
            )
        }
        exec(DOCUMENTS_REBUILD_1_2)
    }

    private fun migrate() = exec(DROP_SCAN_PAGES)

    private fun tableExists(table: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table'",
            ).use { rs -> rs.next() }
        }

    private fun indexNames(table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
        }

    private fun documents(query: String, onRow: (ResultSet) -> Unit): Int {
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

    @Test
    fun `scan_pages is gone after the migration`() {
        givenV2WithScans()
        assertTrue("precondition: scan_pages exists at v2", tableExists("scan_pages"))

        migrate()

        assertFalse(tableExists("scan_pages"))
    }

    @Test
    fun `the scan_pages index goes with the table`() {
        givenV2WithScans()
        assertTrue(indexNames("scan_pages").contains("index_scan_pages_documentId_position"))

        migrate()

        assertFalse(
            "a leftover index name would fail Room's post-migration schema check",
            indexNames("scan_pages").contains("index_scan_pages_documentId_position"),
        )
    }

    @Test
    fun `document rows survive the migration with every value intact`() {
        givenV2WithScans()

        migrate()

        val seen = documents("SELECT * FROM documents ORDER BY id") { rs ->
            when (rs.getLong("id")) {
                1L -> {
                    assertEquals("content://a", rs.getString("uri"))
                    assertEquals("Report", rs.getString("title"))
                    assertEquals("application/pdf", rs.getString("mimeType"))
                    assertEquals("PDF", rs.getString("format"))
                    assertEquals(2048L, rs.getLong("sizeBytes"))
                    assertEquals(3, rs.getInt("pageCount"))
                    assertEquals(111L, rs.getLong("createdAt"))
                    assertEquals(999L, rs.getLong("lastOpenedAt"))
                }

                2L -> {
                    assertEquals("content://b", rs.getString("uri"))
                    assertEquals("Note", rs.getString("title"))
                    assertEquals(null, rs.getString("mimeType"))
                    assertEquals("MARKDOWN", rs.getString("format"))
                }
            }
        }
        assertEquals(2, seen)
    }

    @Test
    fun `the documents indices are untouched`() {
        givenV2WithScans()

        migrate()

        val names = indexNames("documents")
        assertTrue(names.contains("index_documents_uri"))
        assertTrue(names.contains("index_documents_lastOpenedAt"))
    }

    @Test
    fun `a v1 install with no scan_pages still migrates`() {
        // The v1 -> v2 rebuild above never created scan_pages, so the DROP IF EXISTS has to
        // tolerate its absence rather than throwing on the direct v1 -> v3 path.
        givenV1WithoutScans()
        assertFalse("precondition: v1 -> v2 never created scan_pages", tableExists("scan_pages"))

        migrate()

        assertFalse(tableExists("scan_pages"))
        assertEquals(
            1,
            documents("SELECT * FROM documents") { rs -> assertEquals("Report", rs.getString("title")) },
        )
    }

    @Test
    fun `migrating twice is harmless`() {
        givenV2WithScans()
        migrate()

        migrate()

        assertFalse(tableExists("scan_pages"))
        assertEquals(2, documents("SELECT * FROM documents") { })
    }
}
