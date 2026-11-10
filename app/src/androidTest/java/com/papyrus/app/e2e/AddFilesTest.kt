package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddFilesTest : E2eTest() {

    private val app = AppDriver()

    private val picker = SystemPicker()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Before
    fun startWithNoFilesInDownloads() = PublicDownloads.removeAll()

    @After
    fun removeTheFilesPicked() = PublicDownloads.removeAll()

    @Test
    fun oneFileOpensAtOnceAndJoinsTheLibrary() {
        assumeTrue("Needs the Downloads collection of Android 10", PublicDownloads.available)
        val name = PublicDownloads.put("one.txt", TEXT, "Picked on its own".toByteArray())
        app.launchHome()

        app.tap(app.desc("Add files"))
        picker.chooseOne(name)

        app.assertShown(app.label("Picked on its own"), load)
        app.back()
        app.assertShown(app.text(name))
    }

    @Test
    fun severalFilesAreAddedAndReportedWithoutOpeningAny() {
        assumeTrue("Needs the Downloads collection of Android 10", PublicDownloads.available)
        val names = listOf("a.txt", "b.txt", "c.txt").map { PublicDownloads.put(it, TEXT, "Body of $it".toByteArray()) }
        app.launchHome()

        app.tap(app.desc("Add files"))
        picker.chooseSeveral(names)

        app.assertShown(app.text("Added 3 files"), load)
        names.forEach { app.assertShown(app.text(it)) }
        app.assertShown(app.desc("Add files"))
    }

    @Test
    fun aPasswordProtectedFileIsCountedAsSkippedAndNotAdded() {
        assumeTrue("Needs the Downloads collection of Android 10", PublicDownloads.available)
        val plain = PublicDownloads.put("plain.txt", TEXT, "Nothing secret".toByteArray())
        val locked = PublicDownloads.put("locked.odt", ODT, Documents.encryptedOdt())
        app.launchHome()

        app.tap(app.desc("Add files"))
        picker.chooseSeveral(listOf(plain, locked))

        app.assertShown(app.text("Added 1 files"), load)
        // The snackbar shows one message at a time, so the second waits for the first to leave.
        app.assertShown(app.text("Skipped 1 password-protected file(s)"), load)
        app.assertShown(app.text(plain))
        app.assertGone(app.text(locked))
    }

    @Test
    fun theAddButtonIsHiddenWhileSelectingAndReturnsAfterwards() {
        app.addToLibrary(Fixtures.text("notes.txt", "first"))
        app.launchHome()
        app.assertShown(app.desc("Add files"))

        app.longPress(app.text("notes.txt"))
        app.assertShown(app.text("1 selected"))
        app.assertGone(app.desc("Add files"))

        app.back()
        app.assertGone(app.text("1 selected"))
        app.assertShown(app.desc("Add files"))
    }

    private companion object {
        const val TEXT = "text/plain"
        const val ODT = "application/vnd.oasis.opendocument.text"
    }
}
