package io.github.pixelrick420.papyrus.viewer.pptx

/** Resolves an `a:effectLst` into the effects the software canvas can paint. */
internal object Effects {

    fun of(node: XNode?, colors: ColorModel): SceneEffects? {
        node ?: return null
        return SceneEffects(node.children.mapNotNull { child ->
            if (child.name.namespace != OoxmlNamespace.A) return@mapNotNull null
            when (child.name.localName) {
                "outerShdw" -> shadow(child, colors, inner = false, preset = false)
                "innerShdw" -> shadow(child, colors, inner = true, preset = false)
                "prstShdw" -> preset(child, colors)
                else -> null
            }
        })
    }

    private fun preset(node: XNode, colors: ColorModel): SceneEffect.Shadow {
        val (distance, direction, blur) = PRESETS[node.attribute("prst")?.lowercase()] ?: PRESETS.getValue("shdw1")
        return shadow(
            node,
            colors,
            inner = false,
            preset = true,
            distance = node.number("dist", fallback = distance),
            direction = node.number("dir", ROTATION_UNITS, direction),
            blur = node.number("blurRad", fallback = blur),
        )
    }

    private fun shadow(
        node: XNode,
        colors: ColorModel,
        inner: Boolean,
        preset: Boolean,
        distance: Double = 0.0,
        direction: Double = 0.0,
        blur: Double = 0.0,
    ): SceneEffect.Shadow = SceneEffect.Shadow(
        color = node.children.firstNotNullOfOrNull(ColorModel::valueOf)?.let(colors::resolve) ?: Rgba.BLACK,
        blurEmu = node.number("blurRad", fallback = blur),
        distanceEmu = node.number("dist", fallback = distance),
        directionDegrees = node.number("dir", ROTATION_UNITS, direction),
        scaleX = node.number("sx", PERCENT_UNIT, 1.0),
        scaleY = node.number("sy", PERCENT_UNIT, 1.0),
        skewX = node.number("kx", ROTATION_UNITS),
        skewY = node.number("ky", ROTATION_UNITS),
        align = node.attribute("algn"),
        rotateWithShape = node.attribute("rotWithShape") != "0",
        inner = inner,
        preset = preset,
    )

    private fun XNode.number(name: String, unit: Double = 1.0, fallback: Double = 0.0): Double =
        attribute(name)?.toDoubleOrNull()?.let { it / unit } ?: fallback

    private const val ROTATION_UNITS = 60_000.0
    private const val PERCENT_UNIT = 100_000.0
    private const val PT = 12_700.0

    private val PRESETS: Map<String, Triple<Double, Double, Double>> = listOf(
        "shdw1" to Triple(2 * PT, 45.0, 0.0),
        "shdw2" to Triple(2 * PT, 90.0, 0.0),
        "shdw3" to Triple(4 * PT, 135.0, 0.0),
        "shdw4" to Triple(6 * PT, 180.0, 0.0),
        "shdw5" to Triple(8 * PT, 225.0, 0.0),
        "shdw6" to Triple(10 * PT, 270.0, 0.0),
        "shdw7" to Triple(12 * PT, 315.0, 0.0),
        "shdw8" to Triple(14 * PT, 0.0, 0.0),
        "shdw9" to Triple(4 * PT, 45.0, 4 * PT),
        "shdw10" to Triple(6 * PT, 90.0, 6 * PT),
        "shdw11" to Triple(8 * PT, 135.0, 8 * PT),
        "shdw12" to Triple(10 * PT, 180.0, 10 * PT),
        "shdw13" to Triple(12 * PT, 225.0, 12 * PT),
        "shdw14" to Triple(4 * PT, 45.0, 2 * PT),
        "shdw15" to Triple(6 * PT, 90.0, 3 * PT),
        "shdw16" to Triple(8 * PT, 135.0, 4 * PT),
        "shdw17" to Triple(10 * PT, 180.0, 5 * PT),
        "shdw18" to Triple(12 * PT, 225.0, 6 * PT),
        "shdw19" to Triple(3 * PT, 270.0, 2 * PT),
        "shdw20" to Triple(4 * PT, 315.0, 3 * PT),
    ).toMap()
}
