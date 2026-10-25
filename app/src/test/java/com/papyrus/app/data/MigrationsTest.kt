package com.papyrus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the schema history: a bumped version without its migration, a migration that could lose
 *  rows, or a forgotten exported schema. */
class MigrationsTest {

    private fun migration(from: Int, to: Int) = object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) = Unit
    }

    private fun problems(vararg steps: Pair<Int, Int>, baseline: Int = 4, current: Int) =
        Migrations.chainProblems(steps.map { migration(it.first, it.second) }, baseline, current)

    /** Gradle runs unit tests from the module directory; an IDE may run them from the repository root. */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/${AppDatabase::class.java.name}/$version.json"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile } ?: File(relative)
    }

    @Test
    fun `the baseline is the oldest supported schema and never moves above the current one`() {
        assertEquals(4, Migrations.BASELINE_VERSION)
        assertTrue(Migrations.CURRENT_VERSION >= Migrations.BASELINE_VERSION)
    }

    @Test
    fun `the exported schema of the current version exists`() {
        // Room writes it during compilation when exportSchema is on; it must then be committed.
        val file = schemaFile(Migrations.CURRENT_VERSION)
        assertTrue("missing exported schema ${file.path}: build once and commit app/schemas/", file.isFile)
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(file.readText())?.groupValues?.get(1)?.toInt()
        assertEquals("${file.path} is the schema of another version", Migrations.CURRENT_VERSION, version)
    }

    @Test
    fun `every version a migration starts from has its schema exported`() {
        val starts = Migrations.ALL.map { it.startVersion } + Migrations.BASELINE_VERSION
        for (version in starts) {
            assertTrue("missing exported schema for version $version", schemaFile(version).isFile)
        }
    }

    @Test
    fun `the shipped migrations form an unbroken chain from the baseline to the current version`() {
        assertEquals(
            emptyList<String>(),
            Migrations.chainProblems(Migrations.ALL.toList(), Migrations.BASELINE_VERSION, Migrations.CURRENT_VERSION),
        )
    }

    @Test
    fun `no migrations is sound only while the current version is the baseline`() {
        assertEquals(emptyList<String>(), problems(current = 4))
        assertEquals(listOf("no migration from version 4 towards 5"), problems(current = 5))
    }

    @Test
    fun `a contiguous chain is sound`() {
        assertEquals(emptyList<String>(), problems(4 to 5, 5 to 6, current = 6))
    }

    @Test
    fun `a multi-version migration can bridge several versions`() {
        assertEquals(emptyList<String>(), problems(4 to 6, 6 to 7, current = 7))
    }

    @Test
    fun `a gap in the chain is reported`() {
        assertEquals(
            listOf("no migration from version 5 towards 7", "migration 6 -> 7 is not on the chain from 4 to 7"),
            problems(4 to 5, 6 to 7, current = 7),
        )
    }

    @Test
    fun `a bumped version with no migration is reported`() {
        assertEquals(listOf("no migration from version 4 towards 6"), problems(current = 6))
    }

    @Test
    fun `a chain that overshoots the current version is reported`() {
        assertTrue(problems(4 to 6, current = 5).any { "overshoots" in it })
    }

    @Test
    fun `two migrations from the same version are ambiguous`() {
        assertTrue(problems(4 to 5, 4 to 5, current = 5).any { "more than one migration starts at version 4" in it })
    }

    @Test
    fun `a migration that goes backwards is reported without looping`() {
        assertTrue(problems(4 to 3, current = 5).any { "does not move forward" in it })
    }

    @Test
    fun `the shipped migrations start at the baseline and end at the current version`() {
        assertEquals(Migrations.BASELINE_VERSION, Migrations.ALL.first().startVersion)
        assertEquals(Migrations.CURRENT_VERSION, Migrations.ALL.last().endVersion)
        assertEquals(
            listOf(4 to 5, 5 to 6, 6 to 7, 7 to 8),
            Migrations.ALL.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun `the 4 to 5 statements only add, so no existing row can be lost`() {
        // An allow-list of additive statement shapes, which rules out DROP, DELETE, UPDATE and table
        // rebuilds without matching words that legitimately appear in a foreign key ("ON DELETE CASCADE").
        val additive = Regex("^(ALTER TABLE `\\w+` ADD COLUMN |CREATE TABLE IF NOT EXISTS |CREATE INDEX IF NOT EXISTS )")
        for (sql in Migrations.V4_TO_V5_SQL) {
            assertTrue("not an additive statement: $sql", additive.containsMatchIn(sql))
        }
    }

    @Test
    fun `new tables are re-runnable and leave with their document`() {
        val tables = Migrations.V4_TO_V5_SQL.filter { it.startsWith("CREATE TABLE") }
        assertEquals(2, tables.size)
        for (sql in tables) {
            assertTrue("not idempotent: $sql", "IF NOT EXISTS" in sql)
            assertTrue(
                "no cascade to documents: $sql",
                "REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in sql,
            )
        }
    }

    @Test
    fun `the new column has a default, so a v4 row is valid without being rewritten`() {
        val alter = Migrations.V4_TO_V5_SQL.single { it.startsWith("ALTER TABLE") }
        assertTrue("`isFavourite` INTEGER NOT NULL DEFAULT 0" in alter)
    }

    @Test
    fun `a migration from below the baseline is not demanded`() {
        // Older schemas are unsupported: a stray 3 -> 4 is neither required nor an error.
        assertEquals(emptyList<String>(), problems(3 to 4, current = 4))
    }

    @Test
    fun `the 5 to 6 statements can all be re-run, so a half-applied migration can be retried`() {
        assertEquals(6, Migrations.V5_TO_V6_SQL.size)
        for (sql in Migrations.V5_TO_V6_SQL) {
            assertTrue(
                "not re-runnable: $sql",
                Regex("^CREATE (TABLE|VIRTUAL TABLE|TRIGGER) IF NOT EXISTS ").containsMatchIn(sql),
            )
        }
    }

    @Test
    fun `the index table is external-content over the stored text, with the prefix tables the query expects`() {
        val fts = Migrations.V5_TO_V6_SQL.single { it.startsWith("CREATE VIRTUAL TABLE") }

        assertTrue("not FTS4: $fts", "USING FTS4(" in fts)
        // External content is what keeps the words in one place instead of two.
        assertTrue("not external content: $fts", "content=`document_text`" in fts)
        assertTrue("diacritics must fold, or `uber` misses `Über`: $fts", "remove_diacritics=1" in fts)
        assertTrue("SearchQuery prefix-matches from two characters: $fts", "prefix=`2,3`" in fts)
    }

    @Test
    fun `the content table cascades with its document and carries every column the entity declares`() {
        val table = Migrations.V5_TO_V6_SQL.single { it.startsWith("CREATE TABLE") }

        assertTrue(
            "a deleted document must not leave its text behind: $table",
            "REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in table,
        )
        for (column in listOf("rowid", "text", "state", "charCount", "sourceSize", "indexedAt", "indexVersion")) {
            assertTrue("missing column `$column`: $table", "`$column` " in table)
        }
    }

    @Test
    fun `one trigger per write keeps the FTS rows in step with the content table`() {
        val triggers = Migrations.V5_TO_V6_SQL.filter { it.startsWith("CREATE TRIGGER") }

        // Room names and generates exactly these four; anything else would not fire.
        assertEquals(
            listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT"),
            triggers.map { sql ->
                listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")
                    .single { it in sql }
            },
        )
        for (sql in triggers) {
            assertTrue("not scoped to the content table: $sql", "ON `document_text`" in sql)
        }
    }

    @Test
    fun `every 5 to 6 statement is exactly what the exported schema says`() {
        // The migration's statement list and the entities are two sources for one schema; Room
        // compares the result of the migration against the entities on first open and would throw.
        val exported = schemaFile(6).readText().replace(Regex("\\s+"), " ")
        for (sql in Migrations.V5_TO_V6_SQL) {
            // Room exports a table's own CREATE with its name replaced by this placeholder.
            val normalized = sql
                .replace(Regex("(?<=IF NOT EXISTS )`\\w+`")) { "`\${TABLE_NAME}`" }
                .replace(Regex("\\s+"), " ")
                .trim()
            assertTrue(
                "not in the exported schema, so the entities and the migration disagree:\n$normalized",
                normalized in exported,
            )
        }
    }

    @Test
    fun `the exported schema holds no 5 to 6 statement the migration forgot`() {
        // Catches drift in the other direction: a trigger Room generates that is not in the list.
        val exported = schemaFile(6).readText()
        // A table's own CREATE carries `${TABLE_NAME}` instead of its name, so its name is counted
        // from the entity entry; the triggers name the content table outright.
        val tables = Regex("\"tableName\": \"(document_text[^\"]*)\"").findAll(exported).count()
        val triggers = Regex("\"CREATE TRIGGER [^\"]*\"").findAll(exported).count()

        assertEquals(Migrations.V5_TO_V6_SQL.size, tables + triggers)
    }

    @Test
    fun `the 6 to 7 statements only add, and the new table leaves with its document`() {
        for (sql in Migrations.V6_TO_V7_SQL) {
            assertTrue(
                "not an additive, re-runnable statement: $sql",
                Regex("^CREATE (TABLE|INDEX) IF NOT EXISTS ").containsMatchIn(sql),
            )
        }
        val table = Migrations.V6_TO_V7_SQL.single { it.startsWith("CREATE TABLE") }
        assertTrue(
            "a deleted document must not leave its highlights behind: $table",
            "REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in table,
        )
    }

    @Test
    fun `every 6 to 7 statement is exactly what the exported schema says`() {
        val exported = schemaFile(7).readText().replace(Regex("\\s+"), " ")
        for (sql in Migrations.V6_TO_V7_SQL) {
            val normalized = sql
                .replace(Regex("(?<=IF NOT EXISTS )`\\w+`(?= \\()")) { "`\${TABLE_NAME}`" }
                .replace(Regex("(?<= ON )`\\w+`")) { "`\${TABLE_NAME}`" }
                .replace(Regex("\\s+"), " ")
                .trim()
            assertTrue(
                "not in the exported schema, so the entity and the migration disagree:\n$normalized",
                normalized in exported,
            )
        }
    }

    @Test
    fun `the 7 to 8 statements only add, and the new table leaves with its document`() {
        // The allow-list rules out DROP, DELETE, UPDATE and table rebuilds, so no existing highlight
        // can be lost by this step, only given two more columns.
        val additive = Regex("^(ALTER TABLE `\\w+` ADD COLUMN |CREATE TABLE IF NOT EXISTS )")
        for (sql in Migrations.V7_TO_V8_SQL) {
            assertTrue("not an additive statement: $sql", additive.containsMatchIn(sql))
        }
        val table = Migrations.V7_TO_V8_SQL.single { it.startsWith("CREATE TABLE") }
        assertTrue(
            "a deleted document must not leave its cached outline behind: $table",
            "REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in table,
        )
    }

    @Test
    fun `the new highlight columns have defaults, so a v7 row stays valid without being rewritten`() {
        val kind = Migrations.V7_TO_V8_SQL.single { it.startsWith("ALTER TABLE") && "`kind`" in it }
        val note = Migrations.V7_TO_V8_SQL.single { it.startsWith("ALTER TABLE") && "`note`" in it }

        // The default is what makes every existing highlight read back as a plain highlight.
        assertTrue("missing the highlight default: $kind", "`kind` INTEGER NOT NULL DEFAULT 1" in kind)
        assertTrue("a note must be optional: $note", "`note` TEXT" in note && "NOT NULL" !in note)
    }

    @Test
    fun `every 7 to 8 statement is exactly what the exported schema says`() {
        val exported = schemaFile(8).readText().replace(Regex("\\s+"), " ")
        for (sql in Migrations.V7_TO_V8_SQL) {
            val normalized = sql
                .replace(Regex("(?<=IF NOT EXISTS )`\\w+`(?= \\()")) { "`\${TABLE_NAME}`" }
                .replace(Regex("(?<= ON )`\\w+`")) { "`\${TABLE_NAME}`" }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (sql.startsWith("ALTER TABLE")) {
                // Room exports the table as a whole, never the ALTER: the added column's own
                // declaration is what must appear in it, exactly as the entities describe it.
                val column = normalized.substringAfter("ADD COLUMN ")
                assertTrue(
                    "not in the exported highlights table, so the entity and the migration disagree:\n$column",
                    column in exported,
                )
            } else {
                assertTrue(
                    "not in the exported schema, so the entity and the migration disagree:\n$normalized",
                    normalized in exported,
                )
            }
        }
    }
}
