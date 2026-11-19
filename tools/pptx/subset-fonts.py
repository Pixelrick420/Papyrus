#!/usr/bin/env python3
"""Subset and rename the bundled metric-compatible fonts (needs fontTools).

usage: subset-fonts.py CARLITO_DIR CALADEA_DIR LIBERATION_DIR OUT_DIR

Writes OUT_DIR/humanist (Carlito), OUT_DIR/caladea and OUT_DIR/metric (Liberation).

Keeps Latin, Latin Extended, Greek, Cyrillic and the common punctuation, symbol and bullet
blocks. Advance widths, hhea and OS/2 metrics are untouched; hinting is dropped.
The OFL forbids reusing a Reserved Font Name on a modified font, so Carlito and Liberation are
renamed; Caladea declares no Reserved Font Name and keeps its name.
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x0250, 0x02AF), (0x02B0, 0x02FF), (0x0300, 0x036F),
    (0x0370, 0x03FF), (0x0400, 0x052F), (0x1E00, 0x1EFF), (0x1F00, 0x1FFF),
    (0x2000, 0x206F), (0x2070, 0x209F), (0x20A0, 0x20CF), (0x2100, 0x214F), (0x2150, 0x218F),
    (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2500, 0x259F), (0x25A0, 0x25FF), (0x2600, 0x26FF),
    (0xFB00, 0xFB06), (0xFEFF, 0xFEFF), (0xFFFD, 0xFFFD),
]
UNICODES = [c for lo, hi in RANGES for c in range(lo, hi + 1)]
FEATURES = ["kern", "mark", "mkmk", "ccmp", "locl"]
STYLES = {"Regular": "Regular", "Bold": "Bold", "Italic": "Italic", "BoldItalic": "Bold Italic"}

# key -> (source prefix, new family, new file prefix, output subdirectory); None keeps the name
JOBS = {
    "carlito": ("Carlito", "Pptx Humanist", "PptxHumanist", "humanist"),
    "caladea": ("Caladea", None, None, "caladea"),
    "liberation-sans": ("LiberationSans", "Pptx Sans", "PptxSans", "metric"),
    "liberation-serif": ("LiberationSerif", "Pptx Serif", "PptxSerif", "metric"),
    "liberation-mono": ("LiberationMono", "Pptx Mono", "PptxMono", "metric"),
}


def rename(font, family, psfamily, style, style_key):
    name = font["name"]
    full = f"{family} {style}" if style != "Regular" else family
    ps = f"{psfamily}-{style_key}"
    uid = f"{psfamily}-{style_key}-subset"
    for rec in list(name.names):
        if rec.nameID in (7, 8, 9, 10, 11, 12, 16, 17, 21, 22, 25):
            name.removeNames(nameID=rec.nameID)
    for pid, eid, lid in ((3, 1, 0x409), (1, 0, 0)):
        name.setName(family, 1, pid, eid, lid)
        name.setName(style, 2, pid, eid, lid)
        name.setName(uid, 3, pid, eid, lid)
        name.setName(full, 4, pid, eid, lid)
        name.setName(ps, 6, pid, eid, lid)


def subset_one(src, dst, new_family, new_prefix, style_key):
    opts = subset.Options()
    opts.hinting = False
    opts.layout_features = FEATURES
    opts.glyph_names = False
    opts.notdef_outline = True
    opts.legacy_kern = True
    opts.name_IDs = ["*"]
    opts.name_legacy = True
    opts.drop_tables += ["DSIG", "hdmx", "VDMX", "LTSH", "gasp"]
    font = TTFont(src)
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=UNICODES)
    sub.subset(font)
    if new_family:
        rename(font, new_family, new_prefix, STYLES[style_key], style_key)
    font.save(dst)


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__)
    carlito, caladea, liberation, out = (Path(a) for a in argv[1:])
    dirs = {"carlito": carlito, "caladea": caladea,
            "liberation-sans": liberation, "liberation-serif": liberation, "liberation-mono": liberation}
    for key, (prefix, family, new_prefix, sub) in JOBS.items():
        (out / sub).mkdir(parents=True, exist_ok=True)
        for style_key in STYLES:
            src = dirs[key] / f"{prefix}-{style_key}.ttf"
            dst = out / sub / f"{new_prefix or prefix}-{style_key}.ttf"
            subset_one(src, dst, family, new_prefix, style_key)
            print(f"{sub}/{dst.name} {dst.stat().st_size}")


if __name__ == "__main__":
    main(sys.argv)
