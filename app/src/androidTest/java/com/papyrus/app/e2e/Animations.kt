package com.papyrus.app.e2e

internal object Animations {

    private val SCALES = listOf(
        "window_animation_scale",
        "transition_animation_scale",
        "animator_duration_scale",
    )

    private var saved: Map<String, String?>? = null

    fun off() {
        if (saved != null) return
        val current = SCALES.associateWith(::read)
        saved = current
        current.keys.forEach { write(it, "0") }
    }

    fun restore() {
        saved?.forEach { (scale, value) -> write(scale, value) }
    }

    private fun read(scale: String): String? = Shell.run("settings get global $scale")
        .takeUnless { it.isEmpty() || it == "null" }

    private fun write(scale: String, value: String?) {
        Shell.run(if (value == null) "settings delete global $scale" else "settings put global $scale $value")
    }
}
