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

/**
 * Drives the installed app the way a person does: by what is on the screen, with taps and typed text.
 *
 * Nothing here calls into the app. UI Automator reads the accessibility tree, so the same tests run against
 * the minified release build, where R8 has renamed or removed whatever an in-process test would have named
 * (Compose's own test rule included). Selectors are the words on screen, which is also what TalkBack reads.
 */
internal class AppDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context: Context = instrumentation.targetContext
    private val packageName: String = context.packageName

    // Every selector is scoped to the app's package, so nothing the system draws can match.

    fun text(value: String): BySelector = By.pkg(packageName).text(value)

    fun textContains(value: String): BySelector = By.pkg(packageName).textContains(value)

    fun desc(value: String): BySelector = By.pkg(packageName).desc(value)

    fun descStartsWith(value: String): BySelector = By.pkg(packageName).descStartsWith(value)

    /** Words drawn by a `Text`, as opposed to a field that is holding the same words. */
    fun label(value: String): BySelector = By.pkg(packageName).clazz(TEXT_VIEW).textContains(value)

    fun field(): BySelector = By.pkg(packageName).clazz(EDIT_TEXT)

    /** Anything at all of the app's: gone once its last screen has closed. */
    fun anything(): BySelector = By.pkg(packageName)

    fun launchHome() = launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

    /** Another app handing Papyrus a document: the intent a file manager sends. */
    fun openFromAnotherApp(file: Fixture) =
        launch(Intent(Intent.ACTION_VIEW, file.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    private fun launch(intent: Intent) {
        intent.setClassName(packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        if (device.wait(Until.hasObject(By.pkg(packageName).depth(0)), LAUNCH_TIMEOUT_MS) != true) {
            throw AssertionError("Papyrus did not come to the front within ${LAUNCH_TIMEOUT_MS}ms")
        }
        // The package is on screen as soon as the splash window is, and a tap that lands while the system
        // swaps the splash for the activity is dropped. Waiting for the events to stop lets the swap finish.
        device.waitForIdle(SETTLE_TIMEOUT_MS)
    }

    /** The first node matching [selector], waiting for it to appear. */
    fun find(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs)
            ?: throw AssertionError("Nothing matched $selector within ${timeoutMs}ms. On screen: ${onScreen()}")

    fun waitGone(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS): Boolean =
        device.wait(Until.gone(selector), timeoutMs) == true

    fun assertShown(selector: BySelector, timeoutMs: Long = UI_TIMEOUT_MS) {
        find(selector, timeoutMs)
    }

    /** Fails unless [selector] is, or soon is, off the screen: the app may take a frame to take something away. */
    fun assertGone(selector: BySelector, timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        if (!waitGone(selector, timeoutMs)) throw AssertionError("Still on screen: $selector. On screen: ${onScreen()}")
    }

    fun tap(selector: BySelector) = retryStale { find(selector).click() }

    /**
     * Taps [target] and waits for [expected], tapping again if nothing came of the first tap. A tap can be lost
     * to a window that is still changing, and it leaves no trace: the screen just stays as it was. The second
     * tap only happens while [target] is still on screen, so one that did land is never repeated.
     */
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

    /**
     * Taps the lowest match. A sheet slides up over the list it was opened from, and the list's thumbnails
     * can carry the same words as the sheet's chips; the sheet is the one lower on the screen.
     */
    fun tapLowest(selector: BySelector) {
        assertShown(selector)
        retryStale {
            val lowest = device.findObjects(selector).maxByOrNull { it.visibleCenter.y }
                ?: throw AssertionError("Nothing matched $selector. On screen: ${onScreen()}")
            lowest.click()
        }
    }

    /** Sets a field's text in one step, which is how an accessibility service types. */
    fun typeInto(selector: BySelector, value: String) = retryStale { find(selector).setText(value) }

    fun back() {
        device.pressBack()
    }

    /**
     * Scrolls the screen's list down until [selector] is on it; a screen that fits needs no scrolling. A column
     * keeps the rows it has scrolled past, so a match only counts once some of it is inside the screen.
     */
    fun scrollTo(selector: BySelector): UiObject2 {
        findVisible(selector)?.let { return it }
        repeat(MAX_SCROLLS) {
            if (!scrollDown()) {
                // If we can't scroll down, maybe we started past the target — try scrolling up.
                scrollToTop()
                return find(selector)
            }
            findVisible(selector, SCROLL_SETTLE_MS)?.let { return it }
        }
        // Exhausted downward scrolls; try from the top as a last resort.
        scrollToTop()
        return find(selector)
    }

    /** Scrolls up until the list can't scroll further, resetting to a known start position. */
    private fun scrollToTop() {
        var canScroll = true
        for (i in 0 until MAX_SCROLLS) {
            if (!canScroll) break
            canScroll = scrollUp()
            device.waitForIdle(SCROLL_SETTLE_MS)
        }
    }

    /** One accessibility scroll step up. */
    private fun scrollUp(): Boolean {
        val list = awaitScrollable() ?: return false
        if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return false
        device.waitForIdle(SCROLL_SETTLE_MS)
        return true
    }

    /**
     * The first match that is inside the screen. The tree is rebuilt after a scroll, so it can be
     * missing or unreadable for a moment; [timeoutMs] keeps looking until it settles.
     */
    private fun findVisible(selector: BySelector, timeoutMs: Long = 0L): UiObject2? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val match = runCatching { device.findObject(selector) }.getOrNull()
            if (match != null && !match.visibleBounds.isEmpty) return match
            if (SystemClock.uptimeMillis() >= until) return null
            Thread.sleep(FIND_POLL_MS)
        }
    }

    /**
     * Scrolls the list down one step through its own scroll action, the way an accessibility service does,
     * instead of injecting a drag. An injected drag whose fling outlasts the wait leaves its pointer held
     * down, and the system then rejects every later touch on the device.
     */
    private fun scrollDown(): Boolean {
        val list = awaitScrollable() ?: return false
        if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
        device.waitForIdle(SCROLL_SETTLE_MS)
        return true
    }

    /**
     * The app's scrollable list, waiting for it to exist. Right after a screen change the active window can still
     * be the previous screen, or have no tree yet, and a missing list then says nothing about whether the one
     * being scrolled has reached its end: only a scroll action that is refused says that.
     */
    private fun awaitScrollable(): AccessibilityNodeInfo? {
        val until = SystemClock.uptimeMillis() + SCROLLABLE_TIMEOUT_MS
        while (true) {
            val list = instrumentation.uiAutomation.rootInActiveWindow?.let(::findScrollable)
            if (list != null) return list
            if (SystemClock.uptimeMillis() >= until) return null
            Thread.sleep(FIND_POLL_MS)
        }
    }

    /** The first scrollable node of the app's, wherever it sits below [node]. */
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

    /** Where [selector] sits on the screen, retried if the node is replaced while it is being read. */
    fun centerYOf(selector: BySelector): Int = retryStale { find(selector).visibleCenter.y }

    /** Opens the "More options" menu of the Home row titled [title]: the button nearest it, as every row has one. */
    fun openRowMenu(title: String) = retryStale {
        val titleY = find(text(title)).visibleCenter.y
        val button = device.findObjects(desc("More options")).minByOrNull { abs(it.visibleCenter.y - titleY) }
            ?: throw AssertionError("No row menu next to \"$title\". On screen: ${onScreen()}")
        button.click()
    }

    /** The viewer's overflow menu, then [item] from it. */
    fun chooseFromViewerMenu(item: String) {
        tap(desc("More options"))
        tap(text(item))
    }

    /**
     * Another app hands over [file] and the reader saves it to the library, which leaves that document open.
     * Launching Home afterwards shows it in the list.
     */
    fun addToLibrary(file: Fixture) {
        openFromAnotherApp(file)
        assertShown(text(file.name), LOAD_TIMEOUT_MS)
        chooseFromViewerMenu("Save to library")
        assertShown(text("Saved to your library"))
    }

    /** Everything the app is showing, as the message of a test that failed: the screen it was looking at. */
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
        /** What Compose reports for a `Text` and for a text field; the platform class names TalkBack reads. */
        private const val TEXT_VIEW = "android.widget.TextView"
        private const val EDIT_TEXT = "android.widget.EditText"

        // By name, never by class: the activity is kept under its own name in every build, and this file
        // must not reference anything R8 is free to rename.
        private const val MAIN_ACTIVITY = "com.papyrus.app.MainActivity"

        const val LAUNCH_TIMEOUT_MS = 15_000L

        /** Most things settle well inside this, on a slow emulator too. */
        const val UI_TIMEOUT_MS = 10_000L

        /** Opening a document: a PDF is rendered, a book unpacked, and the first run has nothing warm. */
        const val LOAD_TIMEOUT_MS = 20_000L

        /** How long to wait for something that should already be on its way out. */
        const val SETTLE_TIMEOUT_MS = 3_000L

        private const val MAX_SCROLLS = 15

        /** Covers the list settling between one scroll step and the next. */
        private const val SCROLL_SETTLE_MS = 1_000L

        /** Poll interval while waiting for the accessibility tree to rebuild after a scroll. */
        private const val FIND_POLL_MS = 100L
        private const val STALE_RETRIES = 3
        private const val MAX_WORDS_SHOWN = 80

        /** How long a tap gets to show its effect before it is taken as lost. */
        private const val TAP_RESPONSE_MS = 3_000L
        private const val TAP_ATTEMPTS = 3

        /** How long to wait for the screen's list to appear, which a screen that has none never does. */
        private const val SCROLLABLE_TIMEOUT_MS = 5_000L
    }
}
