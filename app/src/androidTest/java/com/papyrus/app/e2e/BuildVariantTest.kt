package com.papyrus.app.e2e

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the end-to-end run is testing what it claims to. The suite is the same on both builds, so a release
 * run that quietly installed an unminified build would pass every other test and prove nothing about R8.
 */
@RunWith(AndroidJUnit4::class)
class BuildVariantTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBuildUnderTestIsEitherTheDebugBuildOrTheMinifiedRelease() {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Not kept by any rule, so minified, they are found only under a name R8 chose.
        val minified = INTERNAL_CLASSES.none { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
        }

        // Debug is debuggable and unminified, release the reverse. Equal means a build of neither kind: a
        // release that lost its minification, or a debug build that gained it.
        assertNotEquals(
            "debuggable=$debuggable, minified=$minified for ${context.packageName}",
            debuggable,
            minified,
        )
    }

    private companion object {
        val INTERNAL_CLASSES = listOf(
            "com.papyrus.app.viewer.PrettyPrint",
            "com.papyrus.app.viewer.Autolinks",
        )
    }
}
