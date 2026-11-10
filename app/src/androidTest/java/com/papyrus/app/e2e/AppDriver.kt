package com.papyrus.app.e2e

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import kotlin.math.abs

internal class AppDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context: Context = instrumentation.targetContext
    private val packageName: String = context.packageName

    fun text(value: String): BySelector = By.pkg(packageName).text(value)

    fun textContains(value: String): BySelector = By.pkg(packageName).textContains(value)

    fun desc(value: String): BySelector = By.pkg(packageName).desc(value)

    fun descStartsWith(value: String): BySelector = By.pkg(packageName).descStartsWith(value)

    fun label(value: String): BySelector = By.pkg(packageName).clazz(TEXT_VIEW).textContains(value)

    fun field(): BySelector = By.pkg(packageName).clazz(EDIT_TEXT)

    fun anything(): BySelector = By.pkg(packageName)

    fun launchHome() = launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

    fun openFromAnotherApp(file: Fixture) =
        launch(Intent(Intent.ACTION_VIEW, file.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    private fun launch(intent: Intent) {
        intent.setClassName(packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        if (device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LAUNCH_TIMEOUT_MS) != true) {
            throw AssertionError("Papyrus did not come to the front within ${LAUNCH_TIMEOUT_MS}ms. ${whoHasTheScreen()}")
        }
        // Wait for events to stop before tapping: a tap that lands while the system swaps the splash for the activity is dropped.
        device.waitForIdle(SETTLE_TIMEOUT_MS)
    }

    fun find(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs)
            ?: throw AssertionError("Nothing matched $selector within ${timeoutMs}ms. On screen: ${onScreen()}")

    fun waitGone(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        device.wait(Until.gone(selector), timeoutMs) == true

    fun assertShown(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        find(selector, timeoutMs)
    }

    fun assertGone(selector: BySelector, timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        if (!waitGone(selector, timeoutMs)) throw AssertionError("Still on screen: $selector. On screen: ${onScreen()}")
    }

    fun tap(selector: BySelector) = retryStale { find(selector).click() }

    // A tap can be lost to a window that is still changing and leaves no trace, so it is repeated if nothing came of it.
    fun tapUntilShown(target: BySelector, expected: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        repeat(TAP_ATTEMPTS - 1) {
            tap(target)
            if (device.wait(Until.hasObject(expected), TAP_RESPONSE_MS) == true) return
            if (!device.hasObject(target)) return assertShown(expected, timeoutMs)
        }
        tap(target)
        assertShown(expected, timeoutMs)
    }

    fun longPress(selector: BySelector) = retryStale { find(selector).longClick() }

    // The sheet is the lowest match: the list it slid over can carry the same words in its thumbnails.
    fun tapLowest(selector: BySelector) {
        assertShown(selector)
        retryStale {
            val lowest = device.findObjects(selector).maxByOrNull { it.visibleCenter.y }
                ?: throw AssertionError("Nothing matched $selector. On screen: ${onScreen()}")
            lowest.click()
        }
    }

    fun typeInto(selector: BySelector, value: String) = retryStale { find(selector).setText(value) }

    fun back() {
        device.pressBack()
    }

    fun leaveTheApp() {
        repeat(BACK_ATTEMPTS) {
            if (!device.hasObject(By.pkg(packageName))) return
            device.pressBack()
            device.waitForIdle(SETTLE_TIMEOUT_MS)
        }
        if (device.hasObject(By.pkg(packageName))) throw AssertionError("The app is still on screen: ${onScreen()}")
    }

    // A column keeps the rows it has scrolled past, so a match only counts once some of it is on screen.
    fun scrollTo(selector: BySelector): UiObject2 {
        findVisible(selector)?.let { return it }
        scrollThrough(selector) { scrollDown() }?.let { return it }
        scrollThrough(selector) { scrollUp() }?.let { return it }
        return find(selector, SETTLE_TIMEOUT_MS)
    }

    // A scroll request is applied on the app's next frame, so a step is confirmed by the screen changing.
    private fun scrollThrough(selector: BySelector, scroll: () -> Boolean): UiObject2? {
        repeat(MAX_SCROLLS) {
            val before = screenText()
            if (!scroll() || !awaitScreenChange(before)) return null
            findVisible(selector, SCROLL_SETTLE_MS)?.let { return it }
        }
        return null
    }

    private fun screenText(): String? {
        repeat(STALE_RETRIES) {
            runCatching {
                device.findObjects(By.pkg(packageName)).joinToString("|") { it.text ?: it.contentDescription ?: "" }
            }.getOrNull()?.let { return it }
            Thread.sleep(FIND_POLL_MS)
        }
        return null
    }

    private fun awaitScreenChange(before: String?): Boolean {
        val until = SystemClock.uptimeMillis() + SCROLL_LAND_TIMEOUT_MS
        var latest = before
        var changed = false
        var changedAt = 0L
        while (SystemClock.uptimeMillis() < until) {
            val now = screenText()
            if (now != null && now != latest) {
                latest = now
                changed = true
                changedAt = SystemClock.uptimeMillis()
            } else if (changed && SystemClock.uptimeMillis() - changedAt >= SCROLL_STILL_MS) {
                return true
            }
            Thread.sleep(FIND_POLL_MS)
        }
        return changed
    }

    private fun scrollUp(): Boolean {
        val list = awaitScrollable() ?: return false
        if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return false
        device.waitForIdle(SCROLL_SETTLE_MS)
        return true
    }

    // The first match that is inside the screen. The tree is rebuilt after a scroll, so it can be
    // missing or unreadable for a moment; [timeoutMs] keeps looking until it settles.
    private fun findVisible(selector: BySelector, timeoutMs: Long = 0L): UiObject2? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val match = runCatching { device.findObject(selector) }.getOrNull()
            if (match != null && !match.visibleBounds.isEmpty) return match
            if (SystemClock.uptimeMillis() >= until) return null
            Thread.sleep(FIND_POLL_MS)
        }
    }

    // Scrolls through the list's own scroll action, as an accessibility service does; an injected drag whose fling outlasts the wait can overshoot.
    private fun scrollDown(): Boolean {
        val list = awaitScrollable() ?: return false
        if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
        device.waitForIdle(SCROLL_SETTLE_MS)
        return true
    }

    // Right after a screen change the active window can still be the previous screen or have no tree yet, so wait for the list.
    private fun awaitScrollable(): AccessibilityNodeInfo? {
        val until = SystemClock.uptimeMillis() + SCROLLABLE_TIMEOUT_MS
        while (true) {
            val list = instrumentation.uiAutomation.rootInActiveWindow?.let(::findScrollable)
            if (list != null) return list
            if (SystemClock.uptimeMillis() >= until) return null
            Thread.sleep(FIND_POLL_MS)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.packageName?.toString() == packageName) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findScrollable(child)?.let { return it }
        }
        return null
    }

    fun scrollToAndTap(selector: BySelector) {
        scrollTo(selector)
        tap(selector)
    }

    fun centerYOf(selector: BySelector): Int = retryStale { find(selector).visibleCenter.y }

    fun openRowMenu(title: String) = retryStale {
        val titleY = find(text(title)).visibleCenter.y
        val button = device.findObjects(desc("More options")).minByOrNull { abs(it.visibleCenter.y - titleY) }
            ?: throw AssertionError("No row menu next to \"$title\". On screen: ${onScreen()}")
        button.click()
    }

    fun chooseFromViewerMenu(item: String) {
        tap(desc("More options"))
        tap(text(item))
    }

    fun addToLibrary(file: Fixture) {
        openFromAnotherApp(file)
        assertShown(text(file.name), LOAD_TIMEOUT_MS)
        chooseFromViewerMenu("Save to library")
        assertShown(text("Saved to your library"))
    }

    // On a failed launch: the focused window and the words every window draws, since a system dialog over the app can be the cause.
    private fun whoHasTheScreen(): String {
        val focus = Shell.run("dumpsys window").lineSequence()
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            .take(MAX_FOCUS_LINES)
            .joinToString(" ") { it.trim() }
        val words = runCatching {
            device.findObjects(By.clazz(TEXT_VIEW))
                .mapNotNull { node ->
                    node.text?.takeIf { it.isNotBlank() }
                        ?.let { "${node.applicationPackage}: ${it.take(MAX_WORDS_SHOWN).replace('\n', ' ')}" }
                }
                .take(MAX_WINDOW_WORDS)
                .joinToString(" | ")
        }.getOrElse { "(the screen could not be read: ${it.message})" }
        return "Focus: ${focus.ifEmpty { "(unknown)" }}. All windows: ${words.ifEmpty { "(no text)" }}"
    }

    fun onScreen(): String = runCatching {
        device.findObjects(By.pkg(packageName))
            .mapNotNull { node -> (node.text ?: node.contentDescription)?.takeIf { it.isNotBlank() }?.take(MAX_WORDS_SHOWN) }
            .joinToString(" | ")
    }.getOrElse { "(the screen could not be read: ${it.message})" }

    // A node can be replaced between finding it and acting on it, as Compose recomposes.
    private fun <T> retryStale(action: () -> T): T {
        var stale: StaleObjectException? = null
        repeat(STALE_RETRIES) {
            try {
                return action()
            } catch (e: StaleObjectException) {
                stale = e
            }
        }
        throw requireNotNull(stale)
    }

    companion object {
        private const val TEXT_VIEW = "android.widget.TextView"
        private const val EDIT_TEXT = "android.widget.EditText"

        // By name, never by class: the activity is kept under its own name in every build, and this file
        // must not reference anything R8 is free to rename.
        private const val MAIN_ACTIVITY = "com.papyrus.app.MainActivity"

        const val LAUNCH_TIMEOUT_MS = 15_000L

        const val UI_TIMEOUT_MS = 10_000L

        const val LOAD_TIMEOUT_MS = 20_000L

        const val SETTLE_TIMEOUT_MS = 3_000L

        private const val MAX_SCROLLS = 15

        private const val SCROLL_SETTLE_MS = 1_000L

        // Frames on the CI emulator (software rendering) have been seen a second apart, so a scroll request gets slack.
        private const val SCROLL_LAND_TIMEOUT_MS = 10_000L

        private const val SCROLL_STILL_MS = 600L

        private const val FIND_POLL_MS = 100L
        private const val STALE_RETRIES = 3
        private const val MAX_WORDS_SHOWN = 80

        private const val MAX_WINDOW_WORDS = 20
        private const val MAX_FOCUS_LINES = 4

        private const val TAP_RESPONSE_MS = 3_000L
        private const val TAP_ATTEMPTS = 3

        private const val BACK_ATTEMPTS = 4

        private const val SCROLLABLE_TIMEOUT_MS = 5_000L
    }
}
