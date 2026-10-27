package com.papyrus.app.viewer

/** EPUBs are built in memory, like [OfficeFixtures]'s Word files: a binary fixture cannot be reviewed in a diff. */
internal object EpubFixtures {

    /** A chapter of the book: [href] is relative to the package directory (`OEBPS/`). */
    class Chapter(val href: String, val xhtml: String, val linear: Boolean = true)

    fun xhtml(body: String, head: String = "<title>t</title>") =
        """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head>$head</head><body>$body</body></html>"""

    const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    /**
     * A whole EPUB. Chapters are `ch0`, `ch1`, ... in manifest order; [spineOrder] lists indices to read
     * them in another order. [nav] and [ncx] are the contents of `nav.xhtml` and `toc.ncx`; [media] are
     * files under `OEBPS/`; [extra] are whole entries by archive path; [coverMetaId] and [coverImageHref] name
     * a cover the two ways EPUB does; [mimetypeLast] puts the mimetype entry at the end of the archive.
     */
    fun epub(
        chapters: List<Chapter>,
        spineOrder: List<Int>? = null,
        nav: String? = null,
        ncx: String? = null,
        media: Map<String, ByteArray> = emptyMap(),
        extra: Map<String, ByteArray> = emptyMap(),
        coverMetaId: String? = null,
        coverImageHref: String? = null,
        mimetypeLast: Boolean = false,
        container: String = CONTAINER,
        opfOverride: String? = null,
    ): ByteArray {
        val items = StringBuilder()
        chapters.forEachIndexed { i, ch ->
            items.append("""<item id="ch$i" href="${ch.href}" media-type="application/xhtml+xml"/>""")
        }
        if (nav != null) items.append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
        if (ncx != null) items.append("""<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
        media.keys.forEach { name ->
            val type = if (name.endsWith(".png")) "image/png" else if (name.endsWith(".svg")) "image/svg+xml" else "image/jpeg"
            val id = "m-" + name.replace(Regex("[^A-Za-z0-9]"), "_")
            val props = if (name == coverImageHref) """ properties="cover-image"""" else ""
            items.append("""<item id="$id" href="$name" media-type="$type"$props/>""")
        }
        val order = spineOrder ?: chapters.indices.toList()
        val spine = order.joinToString("") { i ->
            """<itemref idref="ch$i"${if (chapters[i].linear) "" else """ linear="no""""}/>"""
        }
        val cover = if (coverMetaId != null) """<meta name="cover" content="$coverMetaId"/>""" else ""
        val opf = opfOverride ?: """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title>$cover</metadata>
  <manifest>$items</manifest>
  <spine${if (ncx != null) """ toc="ncx"""" else ""}>$spine</spine>
</package>"""

        val entries = LinkedHashMap<String, ByteArray>()
        if (!mimetypeLast) entries["mimetype"] = "application/epub+zip".toByteArray()
        entries["META-INF/container.xml"] = container.toByteArray()
        entries["OEBPS/content.opf"] = opf.toByteArray()
        chapters.forEach { entries["OEBPS/${it.href}"] = it.xhtml.toByteArray() }
        if (nav != null) entries["OEBPS/nav.xhtml"] = nav.toByteArray()
        if (ncx != null) entries["OEBPS/toc.ncx"] = ncx.toByteArray()
        media.forEach { (name, bytes) -> entries["OEBPS/$name"] = bytes }
        entries.putAll(extra)
        if (mimetypeLast) entries["mimetype"] = "application/epub+zip".toByteArray()
        return OfficeFixtures.zipOf(entries)
    }

    fun chapter(body: String, href: String = "c.xhtml") = Chapter(href, xhtml(body))

    /** An EPUB 3 navigation document: [toc] goes inside `<nav epub:type="toc">`. */
    fun navDoc(toc: String, before: String = "", after: String = "") = xhtml(
        """$before<nav epub:type="toc"><ol>$toc</ol></nav>$after""",
    )

    fun ncxDoc(points: String) =
        """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>$points</navMap></ncx>"""

    fun ncxPoint(title: String, src: String, children: String = "") =
        """<navPoint id="p${title.hashCode()}"><navLabel><text>$title</text></navLabel><content src="$src"/>$children</navPoint>"""

    fun encryption(vararg algorithms: String) =
        """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">${
            algorithms.joinToString("") {
                """<enc:EncryptedData><enc:EncryptionMethod Algorithm="$it"/><enc:CipherData><enc:CipherReference URI="OEBPS/x.bin"/></enc:CipherData></enc:EncryptedData>"""
            }
        }</encryption>"""

    /** A whole `.svg` document; [attrs] go on the root, [inner] is what it draws. */
    fun svg(inner: String, attrs: String = "viewBox=\"0 0 100 50\"") =
        "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" $attrs>$inner</svg>"

    /** Stands in for the Android rasteriser: records the scenes it is asked for and answers with [result] or throws. */
    class FakeRasteriser(private val result: ByteArray? = OfficeFixtures.PNG_1X1, private val fails: Boolean = false) : SvgRasteriser {
        val scenes = ArrayList<SvgScene>()

        override fun rasterise(scene: SvgScene): ByteArray? {
            scenes += scene
            if (fails) throw IllegalStateException("cannot draw")
            return result
        }
    }

    const val IDPF_FONT = "http://www.idpf.org/2008/embedding"
    const val ADOBE_FONT = "http://ns.adobe.com/pdf/enc#RC"
    const val AES = "http://www.w3.org/2001/04/xmlenc#aes128-cbc"
}
