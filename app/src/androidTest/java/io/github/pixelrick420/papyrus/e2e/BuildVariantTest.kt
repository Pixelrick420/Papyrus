package io.github.pixelrick420.papyrus.e2e

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

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
            "io.github.pixelrick420.papyrus.viewer.PrettyPrint",
            "io.github.pixelrick420.papyrus.viewer.Autolinks",
        )
    }
}
