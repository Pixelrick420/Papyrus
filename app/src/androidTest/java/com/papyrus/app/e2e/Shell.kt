package com.papyrus.app.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Runs a command with the shell's permissions, which is how a test changes what only `adb shell` may. */
internal object Shell {

    /** The command's output, trimmed; empty if it could not be run. */
    fun run(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }.trim()
    }.getOrDefault("")
}
