package com.papyrus.app.e2e

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

internal object Shell {

    fun run(command: String): String = runCatching {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }.trim()
    }.getOrDefault("")
}
