#!/usr/bin/env python3
"""Survey PPTX corpus for features tracked by spike S7.

Usage:
  survey_corpus.py [CORPUS_DIR] [--markdown OUT.md]

Scans *.pptx, *.potx, *.ppsx files (zip archives) and counts decks containing:
charts, SmartArt, EMF, EMF+ (best-effort), WordArt, vertical text, embedded fonts, Aptos.
Prints a ranked table and can write it to a markdown file.
"""
from __future__ import annotations

import argparse
import os
import sys
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


EXTS = {".pptx", ".potx", ".ppsx"}

NS = {
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
    "c": "http://schemas.openxmlformats.org/drawingml/2006/chart",
    "dgm": "http://schemas.openxmlformats.org/drawingml/2006/diagram",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "mc": "http://schemas.openxmlformats.org/markup-compatibility/2006",
    "asvg": "http://schemas.microsoft.com/office/drawing/2016/SVG/main",
}


@dataclass
class Counts:
    decks: int = 0
    charts: int = 0
    smartart: int = 0
    emf: int = 0
    emfp: int = 0
    wordart: int = 0
    vertical_text: int = 0
    embedded_fonts: int = 0
    aptos: int = 0


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def scan_file(path: Path) -> dict[str, bool]:
    feats = {k: False for k in [
        "charts", "smartart", "emf", "emfp", "wordart", "vertical_text", "embedded_fonts", "aptos"
    ]}
    try:
        with zipfile.ZipFile(path) as zf:
            names = set(zf.namelist())
            for name in names:
                lower = name.lower()
                if lower.endswith(".emf"):
                    feats["emf"] = True
                if lower.endswith(".wmf"):
                    feats["emf"] = True
            for name in names:
                if not name.endswith(".xml") and not name.endswith(".rels"):
                    continue
                try:
                    data = zf.read(name)
                except Exception:
                    continue
                lower = data.lower()
                if b"chart" in lower and (b"/chart" in lower or b"<c:chart" in lower or b"drawingml/2006/chart" in lower):
                    if name.startswith("ppt/charts/") or b"chartSpace" in data or b"graphicData" in data and b"/chart" in lower:
                        feats["charts"] = True
                if b"drawingml/2006/diagram" in lower or b"<dgm:" in data or b"/diagram" in lower:
                    feats["smartart"] = True
                if b"emf" in lower and b"emf+" in lower:
                    feats["emfp"] = True
                if b"embeddedFont" in data or b"font/embed" in lower:
                    feats["embedded_fonts"] = True
                if b"vert=" in data or b"vert=\"" in data or b"vert='" in data:
                    feats["vertical_text"] = True
                if b"Aptos" in data:
                    feats["aptos"] = True
                if b"wordArt" in data or b"prstTxWarp" in data:
                    feats["wordart"] = True
            # XML-aware checks for better precision
            for name in names:
                if not name.endswith(".xml"):
                    continue
                try:
                    root = ET.fromstring(zf.read(name))
                except Exception:
                    continue
                for el in root.iter():
                    tag = local(el.tag)
                    uri = el.tag.split("}", 1)[0][1:] if "}" in el.tag else ""
                    if tag == "chartSpace" or (uri == NS["c"] and tag in {"chart", "chartSpace"}):
                        feats["charts"] = True
                    if uri == NS["dgm"] or "diagram" in uri:
                        feats["smartart"] = True
                    if tag == "embeddedFontLst" or tag == "embeddedFont":
                        feats["embedded_fonts"] = True
                    if tag == "bodyPr":
                        vert = el.attrib.get("vert")
                        if vert and vert not in {"horz", "eaVert"}:
                            feats["vertical_text"] = True
                        if vert == "eaVert":
                            feats["vertical_text"] = True
                    if tag in {"prstTxWarp", "textOutline", "textFill"}:
                        feats["wordart"] = True
                    for attr, val in el.attrib.items():
                        if "Aptos" in val:
                            feats["aptos"] = True
                    if el.text and "Aptos" in el.text:
                        feats["aptos"] = True
            # EMF+ marker inside EMF binaries (best-effort)
            if feats["emf"]:
                for name in names:
                    if name.lower().endswith(".emf"):
                        try:
                            blob = zf.read(name)
                            if b"EMF+" in blob or b"\x2b\x46\x4d\x45" in blob:
                                feats["emfp"] = True
                        except Exception:
                            pass
    except Exception:
        return feats
    return feats


def survey(corpus_dir: Path) -> Counts:
    counts = Counts()
    for root, _, files in os.walk(corpus_dir):
        for fn in files:
            if Path(fn).suffix.lower() not in EXTS:
                continue
            feats = scan_file(Path(root) / fn)
            counts.decks += 1
            for k, v in feats.items():
                if v:
                    setattr(counts, k, getattr(counts, k) + 1)
    return counts


def ranked_rows(counts: Counts) -> list[tuple[str, int, str]]:
    n = counts.decks or 1
    rows = [
        ("Charts", counts.charts, "M5"),
        ("SmartArt (cached drawings)", counts.smartart, "M5"),
        ("EMF/WMF", counts.emf, "M5"),
        ("EMF+ only (subset of EMF)", counts.emfp, "M5/049"),
        ("WordArt", counts.wordart, "M5/M6"),
        ("Vertical text", counts.vertical_text, "M5/M6"),
        ("Embedded fonts", counts.embedded_fonts, "M5/M6"),
        ("Aptos", counts.aptos, "M5/M6"),
    ]
    rows.sort(key=lambda r: (-r[1], r[0]))
    return rows


def format_table(counts: Counts) -> str:
    rows = ranked_rows(counts)
    n = counts.decks
    lines = [
        f"Corpus decks surveyed: {n}",
        "",
        "| Rank | Feature | Decks | Share | Milestone note |",
        "| --- | --- | ---: | ---: | --- |",
    ]
    for i, (name, c, note) in enumerate(rows, 1):
        share = f"{(c / n * 100):.1f}%" if n else "n/a"
        lines.append(f"| {i} | {name} | {c} | {share} | {note} |")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("corpus_dir", nargs="?", default="tools/pptx/corpus")
    ap.add_argument("--markdown", dest="markdown")
    args = ap.parse_args()
    corpus = Path(args.corpus_dir)
    if not corpus.exists():
        counts = Counts()
    else:
        counts = survey(corpus)
    table = format_table(counts)
    print(table)
    if args.markdown:
        out = Path(args.markdown)
        out.parent.mkdir(parents=True, exist_ok=True)
        header = "# S7 Corpus survey\n\n"
        out.write_text(header + table + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
