package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class SavedDuplicateFinderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `no same title row means the source is never opened`() = runBlocking {
        val bytes = "hello".toByteArray()
        var opens = 0
        val finder = finder(emptyList(), openSource = { opens++; ByteArrayInputStream(bytes) })

        assertNull(finder.find(source(bytes)))
        assertEquals(0, opens)
    }

    @Test
    fun `a same title row of a different size does not open the source`() = runBlocking {
        val bytes = "hello papyrus".toByteArray()
        val row = row(copy(bytes + byteArrayOf(7)))
        var opens = 0
        val finder = finder(listOf(row), openSource = { opens++; ByteArrayInputStream(bytes) })

        assertNull(finder.find(source(bytes)))
        assertEquals(0, opens)
    }

    @Test
    fun `same size but different bytes are hashed and rejected`() = runBlocking {
        val bytes = "hello papyrus".toByteArray()
        val other = "hello papirus".toByteArray()
        val row = row(copy(other))
        var opens = 0
        val finder = finder(listOf(row), openSource = { opens++; ByteArrayInputStream(bytes) })

        assertNull(finder.find(source(bytes)))
        assertEquals(1, opens)
    }

    @Test
    fun `same bytes match the saved copy`() = runBlocking {
        val bytes = "hello papyrus".toByteArray()
        val row = row(copy(bytes))
        var opens = 0
        val finder = finder(listOf(row), openSource = { opens++; ByteArrayInputStream(bytes) })

        assertSame(row, finder.find(source(bytes)))
        assertEquals(1, opens)
    }

    @Test
    fun `an unreadable source falls through to a fresh copy`() = runBlocking {
        val row = row(copy("hello".toByteArray()))
        val finder = finder(listOf(row), openSource = { throw IOException("boom") })

        assertNull(finder.find(source("hello".toByteArray())))
    }

    private fun finder(
        rows: List<DocumentEntity>,
        openSource: () -> java.io.InputStream,
    ): SavedDuplicateFinder = SavedDuplicateFinder(
        rowsByTitle = { rows },
        ownedCopy = { uri -> rows.firstOrNull { it.uri == uri }?.let { File(it.uri.removePrefix("owned://")) } },
        openSource = { openSource() },
    )

    private fun source(bytes: ByteArray) = DocumentEntity(
        uri = "content://source",
        title = TITLE,
        format = DocumentFormat.TEXT,
        sizeBytes = bytes.size.toLong(),
        createdAt = 0,
        lastOpenedAt = 0,
    )

    private fun row(copy: File) = DocumentEntity(
        uri = "owned://${copy.path}",
        title = TITLE,
        format = DocumentFormat.TEXT,
        sizeBytes = copy.length(),
        createdAt = 0,
        lastOpenedAt = 0,
    )

    private fun copy(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    private companion object {
        const val TITLE = "notes.txt"
    }
}
