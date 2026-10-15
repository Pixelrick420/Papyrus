package com.papyrus.app.data

/** The title stored for a document whose provider reports no display name. */
internal const val UNTITLED = "untitled"

/**
 * Whether a file the user re-picked after losing access is the document a row or hand-over stands for.
 * A SAF grant can only come from the user choosing the file again, so this is the only guard against
 * re-pointing a row at some other file that happens to share a prefix of its name.
 *
 * The display name must match exactly, and so must the format. Size is deliberately not compared: the
 * file may have been edited since it was indexed, and that is a legitimate re-pick. The name is compared
 * case-sensitively because providers disagree about case-folding and a looser match only widens what
 * can be wrongly accepted.
 *
 * [pickedName] gets the same blank-name fallback the entity builder used for [expectedTitle], so a
 * document stored as "untitled" is matched by a pick that also reports no name. [pickedFormat] must be
 * resolved the way the stored format was (extension, then MIME type, then a content sniff): the row may
 * hold a format that only the sniff could have produced.
 */
internal fun matchesRepick(
    expectedTitle: String,
    expectedFormat: DocumentFormat,
    pickedName: String,
    pickedFormat: DocumentFormat,
): Boolean =
    pickedName.ifBlank { UNTITLED } == expectedTitle && formatsCompatible(expectedFormat, pickedFormat)

/**
 * Equal formats, with one allowance: a row written before content sniffing existed can say UNKNOWN for
 * a text file that the sniff now resolves to CODE, so those two count as the same.
 */
private fun formatsCompatible(expected: DocumentFormat, picked: DocumentFormat): Boolean =
    expected == picked ||
        (expected == DocumentFormat.UNKNOWN && picked == DocumentFormat.CODE) ||
        (expected == DocumentFormat.CODE && picked == DocumentFormat.UNKNOWN)
