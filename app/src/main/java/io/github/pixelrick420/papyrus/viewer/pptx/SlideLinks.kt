package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.PdfLinkTarget
import io.github.pixelrick420.papyrus.viewer.safeLink

/** Only safe external schemes leave the app; slide and show-jump actions scroll, every other action is ignored. */
internal fun slideLinkTarget(
    action: String?,
    externalUrl: String?,
    targetSlide: Int?,
    current: Int,
    slideCount: Int,
): PdfLinkTarget? {
    val act = action?.trim().orEmpty()
    if (act.isEmpty()) return safeLink(externalUrl)?.let(PdfLinkTarget::External)
    val name = act.removePrefix("ppaction://")
    val index = when {
        name == "hlinksldjump" -> targetSlide
        name.startsWith("hlinkshowjump?jump=") -> when (name.substringAfter("jump=")) {
            "nextslide" -> current + 1
            "previousslide" -> current - 1
            "firstslide" -> 0
            "lastslide" -> slideCount - 1
            else -> null
        }
        else -> null
    }
    return index?.takeIf { it in 0 until slideCount }?.let(PdfLinkTarget::Internal)
}
