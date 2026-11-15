#!/usr/bin/env python3
"""Generate the calibration control decks and their case manifest (needs python-pptx).

usage: make_control_decks.py OUT_DIR [--fonts DIR]

Writes OUT_DIR/C-LNSP.pptx + C-LNSP.cases.json and the same pair for C-PARA. Every case is one
slide holding one top-anchored, zero-inset shape, so a person with PowerPoint can export the deck
to PDF and hand the manifest and the PDF to extract_reference.py. Sweeps follow plan Appendix A:
one variable per deck, fonts at 100 % scale.

The manifest carries each case's font ascent and descent, read from the installed font file, because
a PDF only records where a line box ends and the extractor needs them to recover the baseline.
"""
import json
import struct
import subprocess
import sys
from pathlib import Path

from pptx import Presentation
from pptx.util import Inches, Pt

FONTS = [
    "Calibri", "Arial", "Times New Roman", "Cambria", "Courier New", "Segoe UI", "Aptos",
    "Noto Sans CJK SC", "Noto Naskh Arabic",
]
SIZES = [8, 10, 12, 14, 18, 24, 36, 72, 200]
LINE_SPACINGS = [
    ("lnSpcPct", 80), ("lnSpcPct", 90), ("lnSpcPct", 100), ("lnSpcPct", 114),
    ("lnSpcPct", 150), ("lnSpcPct", 200), ("lnSpcPts", 10), ("lnSpcPts", 14), ("lnSpcPts", 24),
]
PROSE = "The quick brown fox jumps over the lazy dog"
PARAGRAPHS = ["The quick brown fox", "jumps over the lazy dog", "Pack my box with five dozen jugs"]
PARA_FONTS = ["Arial", "Calibri", "Times New Roman"]
PARA_SPACINGS = [
    ("spcBefPct", 0), ("spcBefPct", 50), ("spcBefPct", 100), ("spcBefPct", 200),
    ("spcAftPct", 0), ("spcAftPct", 50), ("spcAftPct", 100), ("spcAftPct", 200),
    ("spcBefPts", 0), ("spcBefPts", 6), ("spcBefPts", 12), ("spcBefPts", 24),
    ("spcAftPts", 0), ("spcAftPts", 6), ("spcAftPts", 12), ("spcAftPts", 24),
]
EMPTY_SIZES = [8, 18, 36]


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    out = Path(argv[1])
    out.mkdir(parents=True, exist_ok=True)
    metrics = {family: font_metrics(family) for family in FONTS + PARA_FONTS}
    line_cases = line_spacing_cases(metrics)
    para_cases = paragraph_spacing_cases(metrics)
    build(out, "C-LNSP", [(case, PROSE) for case in line_cases])
    build(out, "C-PARA", [(case, paragraphs) for case in para_cases])
    return 0


def line_spacing_cases(metrics):
    cases = []
    for font in FONTS:
        for size in SIZES:
            for key, value in LINE_SPACINGS:
                cases.append(case(f"{slug(font)}-{size}-{key}{value}", font, size, {key: value}, metrics))
    for font in FONTS:
        cases.append(case(f"{slug(font)}-mixed-sizes", font, 18, {"lnSpcPct": 100}, metrics, sizes=[12, 36]))
    return cases


def paragraph_spacing_cases(metrics):
    cases = []
    for font in PARA_FONTS:
        for key, value in PARA_SPACINGS:
            cases.append(case(f"{slug(font)}-18-{key}{value}", font, 18, {key: value}, metrics, paragraphs=PARAGRAPHS))
        for size in EMPTY_SIZES:
            cases.append(
                case(f"{slug(font)}-18-empty-{size}", font, 18, {}, metrics, paragraphs=PROSE, emptySize=size)
            )
    return cases


def case(case_id, family, size, spacing, metrics, **extra):
    ascent, descent = metrics.get(family) or (None, None)
    entry = {
        "id": case_id,
        "font": family,
        "sizePt": size,
        "ascentEm": ascent,
        "descentEm": descent,
    }
    entry.update(spacing)
    entry.update(extra)
    return entry


def build(out, deck, cases):
    presentation = Presentation()
    presentation.slide_width = Inches(13.333)
    presentation.slide_height = Inches(7.5)
    blank = presentation.slide_layouts[6]
    manifest = {"deck": deck, "cases": []}
    for number, (entry, content) in enumerate(cases, start=1):
        slide = presentation.slides.add_slide(blank)
        frame = slide.shapes.add_textbox(0, 0, presentation.slide_width, presentation.slide_height).text_frame
        frame.word_wrap = False
        frame.margin_left = frame.margin_right = frame.margin_top = frame.margin_bottom = 0
        if isinstance(content, list):
            fill_paragraphs(frame, content, entry)
        else:
            fill_prose(frame, content, entry)
        manifest["cases"].append(dict(published(entry), slide=number))
    (out / f"{deck}.pptx").save(out / f"{deck}.pptx")
    (out / f"{deck}.cases.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"{deck}: {len(manifest['cases'])} slides")


def fill_prose(frame, text, entry):
    paragraph = frame.paragraphs[0]
    apply_spacing(paragraph, entry)
    sizes = entry.get("sizes") or [entry["sizePt"]]
    for index, size in enumerate(sizes):
        run = paragraph.add_run()
        run.text = text if index == 0 else f" {text}"
        run.font.name = entry["font"]
        run.font.size = Pt(size)


def fill_paragraphs(frame, texts, entry):
    for index, text in enumerate(texts):
        paragraph = frame.paragraphs[0] if index == 0 else frame.add_paragraph()
        apply_spacing(paragraph, entry)
        paragraph.text = text
        paragraph.font.name = entry["font"]
        paragraph.font.size = Pt(entry["sizePt"])
    if entry.get("emptySize"):
        frame.add_paragraph().font.size = Pt(entry["emptySize"])


def published(entry):
    return {key: value for key, value in entry.items() if key not in ("sizes", "emptySize")}


def apply_spacing(paragraph, entry):
    for key, value in entry.items():
        if key.startswith("lnSpc"):
            paragraph.line_spacing = value / 100 if key == "lnSpcPct" else Pt(value)
        elif key.startswith("spcBef"):
            paragraph.space_before = value / 100 if key == "spcBefPct" else Pt(value)
        elif key.startswith("spcAft"):
            paragraph.space_after = value / 100 if key == "spcAftPct" else Pt(value)


def slug(family):
    return family.lower().replace(" ", "-")


def font_metrics(family):
    path = font_file(family)
    if not path:
        return None
    data = Path(path).read_bytes()
    tables = table_directory(data)
    head, hhea, os2 = tables.get("head"), tables.get("hhea"), tables.get("OS/2")
    if not head or not hhea:
        return None
    units = unsigned(data, head + 18, 2)
    if units == 0:
        return None
    if os2 is not None:
        return os2_metrics(data, os2, units, hhea)
    return signed(data, hhea + 4, 2) / units, -signed(data, hhea + 6, 2) / units


def os2_metrics(data, os2, units, hhea):
    selection = unsigned(data, os2 + 62, 2)
    if selection & 0x80:
        ascent = signed(data, os2 + 68, 2)
        descent = -signed(data, os2 + 70, 2)
        return ascent / units, descent / units
    if unsigned(data, os2, 2) >= 2:
        return unsigned(data, os2 + 74, 2) / units, unsigned(data, os2 + 76, 2) / units
    return signed(data, hhea + 4, 2) / units, -signed(data, hhea + 6, 2) / units


def font_file(family):
    query = ["fc-match", "-f", "%{file}", family]
    try:
        result = subprocess.run(query, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    path = result.stdout.strip()
    return path if path and Path(path).suffix.lower() == ".ttf" else None


def table_directory(data):
    if len(data) < 12:
        return {}
    version = struct.unpack(">L", data[:4])[0]
    if version not in (0x00010000, 0x4F54544F):
        return {}
    count = unsigned(data, 4, 2)
    tables = {}
    for index in range(count):
        record = 12 + index * 16
        if record + 16 > len(data):
            break
        tag = data[record:record + 4].decode("ascii", "replace")
        offset = struct.unpack(">L", data[record + 8:record + 12])[0]
        length = struct.unpack(">L", data[record + 12:record + 16])[0]
        if offset + length <= len(data):
            tables[tag] = offset
    return tables


def unsigned(data, offset, size):
    return int.from_bytes(data[offset:offset + size], "big")


def signed(data, offset, size):
    return int.from_bytes(data[offset:offset + size], "big", signed=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
