package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * The named character references of HTML 4, which XML does not define. Real-world XHTML is full of
 * `&nbsp;` and `&mdash;`, and a strict parser treats each as an error that would drop the whole chapter.
 * They are handed to the parser through the standard `defineEntityReplacementText`, which Android's
 * parser and kxml2 share (Android's is a descendant of it), so a chapter reads the same in the app and in
 * a JVM test. The five XML ones (`amp`, `lt`, `gt`, `quot`, `apos`) are the parser's own.
 */
internal object EpubEntities {

    /** U+00A0 to U+00FF in order: the entity of code point `0xA0 + i` is `LATIN1[i]`. */
    private val LATIN1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
            "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
            "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
        ).split(' ')

    private const val LATIN1_START = 0xA0

    /** Greek capitals from Alpha (U+0391); U+03A2 has no letter, so Sigma follows Rho at U+03A3. */
    private val GREEK_CAPITAL = listOf(
        "Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta", "Iota", "Kappa", "Lambda", "Mu",
        "Nu", "Xi", "Omicron", "Pi", "Rho", null, "Sigma", "Tau", "Upsilon", "Phi", "Chi", "Psi", "Omega",
    )

    /** Greek small letters from alpha (U+03B1); U+03C2 is the final sigma. */
    private val GREEK_SMALL = listOf(
        "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu",
        "nu", "xi", "omicron", "pi", "rho", "sigmaf", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
    )

    private const val GREEK_CAPITAL_START = 0x391
    private const val GREEK_SMALL_START = 0x3B1

    private val OTHERS: Map<String, Int> = mapOf(
        "OElig" to 338, "oelig" to 339, "Scaron" to 352, "scaron" to 353, "Yuml" to 376, "fnof" to 402,
        "circ" to 710, "tilde" to 732, "thetasym" to 977, "upsih" to 978, "piv" to 982,
        "ensp" to 8194, "emsp" to 8195, "thinsp" to 8201, "zwnj" to 8204, "zwj" to 8205, "lrm" to 8206, "rlm" to 8207,
        "ndash" to 8211, "mdash" to 8212, "lsquo" to 8216, "rsquo" to 8217, "sbquo" to 8218,
        "ldquo" to 8220, "rdquo" to 8221, "bdquo" to 8222, "dagger" to 8224, "Dagger" to 8225,
        "bull" to 8226, "hellip" to 8230, "permil" to 8240, "prime" to 8242, "Prime" to 8243,
        "lsaquo" to 8249, "rsaquo" to 8250, "oline" to 8254, "frasl" to 8260, "euro" to 8364,
        "image" to 8465, "weierp" to 8472, "real" to 8476, "trade" to 8482, "alefsym" to 8501,
        "larr" to 8592, "uarr" to 8593, "rarr" to 8594, "darr" to 8595, "harr" to 8596, "crarr" to 8629,
        "lArr" to 8656, "uArr" to 8657, "rArr" to 8658, "dArr" to 8659, "hArr" to 8660,
        "forall" to 8704, "part" to 8706, "exist" to 8707, "empty" to 8709, "nabla" to 8711, "isin" to 8712,
        "notin" to 8713, "ni" to 8715, "prod" to 8719, "sum" to 8721, "minus" to 8722, "lowast" to 8727,
        "radic" to 8730, "prop" to 8733, "infin" to 8734, "ang" to 8736, "and" to 8743, "or" to 8744,
        "cap" to 8745, "cup" to 8746, "int" to 8747, "there4" to 8756, "sim" to 8764, "cong" to 8773,
        "asymp" to 8776, "ne" to 8800, "equiv" to 8801, "le" to 8804, "ge" to 8805, "sub" to 8834,
        "sup" to 8835, "nsub" to 8836, "sube" to 8838, "supe" to 8839, "oplus" to 8853, "otimes" to 8855,
        "perp" to 8869, "sdot" to 8901, "lceil" to 8968, "rceil" to 8969, "lfloor" to 8970, "rfloor" to 8971,
        "lang" to 9001, "rang" to 9002, "loz" to 9674, "spades" to 9824, "clubs" to 9827, "hearts" to 9829,
        "diams" to 9830,
    )

    /** Every entity this object defines, name to its text. */
    val ALL: Map<String, String> = buildMap {
        LATIN1.forEachIndexed { i, name -> put(name, String(Character.toChars(LATIN1_START + i))) }
        GREEK_CAPITAL.forEachIndexed { i, name -> if (name != null) put(name, String(Character.toChars(GREEK_CAPITAL_START + i))) }
        GREEK_SMALL.forEachIndexed { i, name -> put(name, String(Character.toChars(GREEK_SMALL_START + i))) }
        OTHERS.forEach { (name, code) -> put(name, String(Character.toChars(code))) }
    }

    /**
     * Teaches [parser] every entity. Call it after `setInput` (both parsers drop what was defined before it)
     * and never with DOCTYPE processing on, which would also fetch the document's DTD. A parser that
     * refuses is left as it was: the entities then fail the chapter, as they would have anyway.
     */
    fun defineAll(parser: XmlPullParser) {
        try {
            for ((name, text) in ALL) parser.defineEntityReplacementText(name, text)
        } catch (_: XmlPullParserException) {
            // Not fatal: see above.
        } catch (_: IllegalStateException) {
            // Android's parser says so this way when asked at the wrong moment.
        }
    }
}
