#!/usr/bin/env python3
"""Turn an exported control deck into the reference JSON the layout tests read (needs poppler).

usage: extract_reference.py CASES_JSON PDF OUT_JSON [--build 16.x] [--oracle PowerPoint]

CASES_JSON is the manifest make_control_decks.py wrote beside the deck; PDF is that deck exported
from PowerPoint. When PowerPoint is not at hand, `soffice --headless --convert-to pdf DECK.pptx` is
the fallback oracle, which proves less and must be named with --oracle. Line boxes come from
`pdftotext -bbox-layout`; the baseline is the line's bottom minus the case font's descent, which is
why the manifest records ascent and descent.

Only numbers are written: text, x, baseline, width and the paragraph's total height, all in points.
"""
import json
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree


def main(argv):
    if len(argv) < 4:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    manifest_path, pdf_path, out_path = Path(argv[1]), Path(argv[2]), Path(argv[3])
    options = parse_options(argv[4:])
    manifest = json.loads(manifest_path.read_text())
    pages = pages_of(pdf_path)
    cases = [measure(case, pages) for case in manifest["cases"]]
    reference = {
        "deck": manifest["deck"],
        "powerpointBuild": options.get("build", "unknown"),
        "oracle": options.get("oracle", "PowerPoint"),
        "cases": cases,
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(reference, indent=2) + "\n")
    print(f"{reference['deck']}: {len(cases)} cases -> {out_path}")
    return 0


def parse_options(args):
    options = {}
    for index in range(0, len(args) - 1, 2):
        if args[index] not in ("--build", "--oracle"):
            continue
        options[args[index][2:]] = args[index + 1]
    return options


def pages_of(pdf_path):
    result = subprocess.run(
        ["pdftotext", "-bbox-layout", str(pdf_path), "-"],
        capture_output=True,
        text=True,
        check=True,
    )
    return ElementTree.fromstring(result.stdout).findall(".//page")


def measure(case, pages):
    page = pages[case["slide"] - 1]
    descent = (case.get("descentEm") or 0.0) * case["sizePt"]
    boxes = [line_box(line) for line in page.findall(".//line")]
    lines = [box for box in boxes if box is not None]
    case = {key: value for key, value in case.items() if key != "slide"}
    case["lines"] = [{key: value for key, value in line.items() if key != "top"} for line in lines]
    case["textHeightPt"] = round(boxes_height(boxes), 2)
    for line in case["lines"]:
        line["baseline"] = round(line["baseline"] - descent, 2)
    return case


def line_box(line):
    words = [word.text or "" for word in line.findall("word")]
    if not words:
        return None
    left, right = float(line.get("xMin")), float(line.get("xMax"))
    top, bottom = float(line.get("yMin")), float(line.get("yMax"))
    return {"text": " ".join(words), "x": round(left, 2), "baseline": bottom, "width": round(right - left, 2), "top": top}


def boxes_height(boxes):
    measured = [box for box in boxes if box is not None]
    if not measured:
        return 0.0
    return max(box["baseline"] for box in measured) - measured[0]["top"]


if __name__ == "__main__":
    sys.exit(main(sys.argv))
