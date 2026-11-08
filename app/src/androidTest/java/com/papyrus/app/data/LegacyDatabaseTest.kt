package com.papyrus.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LegacyDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacy = context.getDatabasePath("papyrus.db")
    private val sidecars = listOf("-wal", "-shm", "-journal").map { File(legacy.path + it) }

    @After
    fun cleanUp() {
        context.deleteDatabase("papyrus.db")
        sidecars.forEach { it.delete() }
    }

    @Test
    fun aLegacyDatabaseAndItsSidecarsGo() {
        legacy.parentFile?.mkdirs()
        legacy.writeText("old")
        sidecars.forEach { it.writeText("old") }

        LegacyDatabase.deleteIfPresent(context)

        assertFalse(legacy.exists())
        assertTrue(sidecars.none { it.exists() })
    }

    @Test
    fun aFreshInstallKeepsItsFiles() {
        val control = File(context.filesDir, "legacy-database-test-keep")
        control.writeText("keep")
        try {
            LegacyDatabase.deleteIfPresent(context)
            assertTrue(control.exists())
        } finally {
            control.delete()
        }
    }
}
