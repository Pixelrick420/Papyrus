package io.github.pixelrick420.papyrus.e2e

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal class SystemPicker {

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val picker: Pattern = Pattern.compile("com\\.(google\\.)?android\\.documentsui")

    fun chooseOne(name: String) {
        arriveAt(name)
        find(By.pkg(picker).text(name)).click()
    }

    fun chooseSeveral(names: List<String>) {
        arriveAt(names.first())
        find(By.pkg(picker).text(names.first())).longClick()
        for (name in names.drop(1)) find(By.pkg(picker).text(name)).click()
        find(By.pkg(picker).text("Select")).click()
    }

    private fun arriveAt(name: String) {
        if (device.wait(Until.hasObject(By.pkg(picker)), OPEN_TIMEOUT_MS) != true) {
            throw AssertionError("The system picker did not open. On screen: ${onScreen()}")
        }
        if (device.wait(Until.hasObject(By.pkg(picker).text(name)), LOOK_MS) == true) return
        // It opened somewhere else, or on Recent. The drawer lists the roots, and the last match is the drawer's:
        // it is drawn over the folder, whose own title can say the same word.
        device.findObject(By.pkg(picker).desc("Show roots"))?.click()
        device.wait(Until.hasObject(By.pkg(picker).text("Downloads")), OPEN_TIMEOUT_MS)
        device.findObjects(By.pkg(picker).text("Downloads")).lastOrNull()?.click()
        if (device.wait(Until.hasObject(By.pkg(picker).text(name)), OPEN_TIMEOUT_MS) != true) {
            throw AssertionError("The picker does not list $name. On screen: ${onScreen()}")
        }
    }

    private fun find(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), OPEN_TIMEOUT_MS)
            ?: throw AssertionError("Nothing matched $selector in the picker. On screen: ${onScreen()}")

    private fun onScreen(): String = runCatching {
        device.findObjects(By.pkg(picker))
            .mapNotNull { node -> (node.text ?: node.contentDescription)?.takeIf { it.isNotBlank() } }
            .joinToString(" | ")
    }.getOrElse { "(the screen could not be read: ${it.message})" }

    private companion object {
        const val OPEN_TIMEOUT_MS = 10_000L

        const val LOOK_MS = 3_000L
    }
}
