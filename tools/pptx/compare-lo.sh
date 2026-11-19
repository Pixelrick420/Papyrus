#!/usr/bin/env bash
# Non-blocking LibreOffice cross-check: rasterises page 1 of a deck and puts it beside a Papyrus render.
#
# LibreOffice draws PowerPoint decks its own way, so a difference is "look here", never truth. The deck is copied
# first and every `normAutofit` is frozen to its stored scale, so LibreOffice draws the layout PowerPoint saved
# instead of shrinking the text again.
#
# usage: compare-lo.sh DECK.pptx EXPECTED_PNG [OUT_HTML]
# Exits 0 when soffice or pdftoppm is absent, because the check never blocks anything.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

if [ "$#" -lt 2 ]; then
  echo "usage: $0 DECK.pptx EXPECTED_PNG [OUT_HTML]" >&2
  exit 2
fi

deck="$1"
expected="$2"
out="${3:-${deck%.*}-lo-report.html}"

for tool in soffice pdftoppm; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "skipped: $tool is not installed (the cross-check is optional)" >&2
    exit 0
  fi
done

if [ ! -f "$deck" ] || [ ! -f "$expected" ]; then
  echo "missing deck or expected PNG" >&2
  exit 2
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

frozen="$tmp/frozen.pptx"
python3 - "$deck" "$frozen" <<'PY'
import re
import sys
import zipfile

source, target = sys.argv[1], sys.argv[2]
NORM = re.compile(r"<a:normAutofit\b([^>]*)/?>")
SPAN = re.compile(r"<p:sp>.*?</p:sp>", re.S)
SCALE = re.compile(r'fontScale="(\d+)"')
REDUCTION = re.compile(r'lnSpcReduction="(\d+)"')
SIZE = re.compile(r'(\bsz=")(\d+)(")')
LINE = re.compile(r'(<a:spcPct val=")(\d+)(")')


def freeze(block: str) -> str:
    tag = NORM.search(block)
    if tag is None:
        return block
    attributes = tag.group(1)
    scale = int(SCALE.search(attributes).group(1)) if SCALE.search(attributes) else 100000
    reduction = (
        int(REDUCTION.search(attributes).group(1)) if REDUCTION.search(attributes) else 0
    )
    if scale != 100000:
        block = SIZE.sub(
            lambda m: f"{m.group(1)}{max(100, round(int(m.group(2)) * scale / 100000))}{m.group(3)}",
            block,
        )
    if reduction:
        block = LINE.sub(
            lambda m: f"{m.group(1)}{max(0, round(int(m.group(2)) * (100000 - reduction) / 100000))}{m.group(3)}",
            block,
        )
    return NORM.sub("<a:noAutofit/>", block, count=1)


with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if re.fullmatch(r"ppt/slides/slide\d+\.xml", item.filename):
            data = SPAN.sub(lambda m: freeze(m.group(0)), data.decode("utf-8")).encode("utf-8")
        zout.writestr(item, data)
PY

soffice --headless --norestore \
  "-env:UserInstallation=file://$tmp/profile" \
  --convert-to pdf --outdir "$tmp" "$frozen" >/dev/null 2>&1

pdf="$tmp/$(basename "${frozen%.pptx}").pdf"
if [ ! -f "$pdf" ]; then
  echo "skipped: LibreOffice could not convert $(basename "$deck")" >&2
  exit 0
fi

width="$(od -An -tu4 -j16 -N4 --endian=big "$expected" | tr -d '[:space:]')"
[ -n "$width" ] || width=1280

mkdir -p "$tmp/pair"
cp "$expected" "$tmp/pair/slide-1.png"
pdftoppm -png -f 1 -l 1 -singlefile -scale-to-x "$width" -scale-to-y -1 "$pdf" "$tmp/pair/slide-1-rendered"
mv "$tmp/pair/slide-1-rendered.png" "$tmp/pair/slide-1-libreoffice.png" 2>/dev/null || true

rendered="$tmp/pair/slide-1-libreoffice.png"
if [ ! -f "$rendered" ]; then
  echo "skipped: pdftoppm produced no page" >&2
  exit 0
fi

mkdir -p "$tmp/actual"
mv "$rendered" "$tmp/actual/slide-1.png"
python3 "$here/diff-report" "$tmp/pair" "$tmp/actual" --out "$out" \
  --title "LibreOffice cross-check: $(basename "$deck")"
echo "wrote $out (LibreOffice is a lower-trust oracle: review, do not trust)"
