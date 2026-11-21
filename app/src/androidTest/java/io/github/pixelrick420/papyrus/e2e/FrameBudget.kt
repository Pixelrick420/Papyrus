package io.github.pixelrick420.papyrus.e2e

internal object FrameBudget {

    // gfxinfo reports this since API 21; a null result means the device does not expose frame stats.
    fun percentile95Ms(packageName: String): Int? {
        val output = Shell.run("dumpsys gfxinfo $packageName")
        return Regex("""95th percentile:\s*(\d+)ms""").find(output)?.groupValues?.get(1)?.toIntOrNull()
    }
}
