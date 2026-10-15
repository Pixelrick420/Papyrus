package com.papyrus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the schema history. Today the chain is empty and trivially sound; the point is the day someone
 * bumps [Migrations.CURRENT_VERSION] and forgets the migration, or forgets to commit the exported schema.
 */
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
    fun `a migration from below the baseline is not demanded`() {
        // Older schemas are unsupported: a stray 3 -> 4 is neither required nor an error.
        assertEquals(emptyList<String>(), problems(3 to 4, current = 4))
    }
}
