package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.LaidOutText

/** A stretch of one line that one font draws, positioned in points from the frame's left. */
internal class GlyphRun(
    val start: Int,
    val end: Int,
    val leftPt: Double,
    val rightPt: Double,
    val baselinePt: Double,
    val font: ResolvedFont,
    val sizePt: Double,
    val properties: TextRunProperties,
    val rtl: Boolean,
)

/** Where one character sits, which is what find, selection, TTS and link hit-testing step through. */
internal class CharacterBox(
    val start: Int,
    val end: Int,
    val leftPt: Double,
    val rightPt: Double,
    val topPt: Double,
    val bottomPt: Double,
)

/** One laid-out line: its paragraph and text range, its box in points, and what it draws. */
internal class TextLine(
    val index: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val leftPt: Double,
    val topPt: Double,
    val widthPt: Double,
    val heightPt: Double,
    val baselinePt: Double,
    val runs: List<GlyphRun>,
    val characters: List<CharacterBox>,
    val bullet: BulletMarker = BulletMarker.None,
    val markerLeftPt: Double = 0.0,
    val markerRightPt: Double = 0.0,
)

/** The laid-out text frame: lines on down, each with its runs and per-character boxes (plan §4.7 step 10). */
internal class TextFrameLayout(
    val lines: List<TextLine>,
    val widthPt: Double,
    val heightPt: Double,
    val orientation: TextOrientation = TextOrientation.NONE,
) : LaidOutText
