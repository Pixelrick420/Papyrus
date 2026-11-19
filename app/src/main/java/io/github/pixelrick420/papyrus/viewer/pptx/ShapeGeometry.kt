package io.github.pixelrick420.papyrus.viewer.pptx

/**
 * Resolves the geometry a node stores — an inline custom path, else a named preset from [presets] — for one
 * box size. A connector's stored geometry is drawn as-is; its routing is never recomputed. Flips and rotation
 * stay on the node and are applied by the paint pass through [Affine.forShape].
 */
internal class ShapeGeometry(
    private val presets: PresetGeometry,
    private val builder: PathBuilder = PathBuilder(),
) {

    fun outline(shape: SceneShape): PresetOutline? =
        outline(shape.preset, shape.customGeometry, shape.frame)

    fun outline(connector: SceneConnector): PresetOutline? =
        outline(connector.preset, connector.customGeometry, connector.frame)

    fun outline(picture: ScenePicture): PresetOutline? =
        outline(picture.mask, picture.customGeometry, picture.frame)

    private fun outline(preset: ScenePreset?, custom: PresetShape?, frame: SceneRect?): PresetOutline? {
        if (frame == null) return null
        if (custom != null) return builder.build(custom, frame.width, frame.height)
        val name = preset?.preset ?: return null
        val shape = presets.shape(name) ?: return null
        return builder.build(shape, frame.width, frame.height, preset.adjusts)
    }
}
