#!/usr/bin/env bash
# Downloads Apache POI's public slideshow test decks for the optional smoke test.
# They go to the git-ignored tools/pptx/corpus/ and are never committed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/corpus/poi"

if [ -d "$dest/.git" ]; then
  echo "already downloaded; delete $here/corpus to refresh" >&2
else
  mkdir -p "$here/corpus"
  tmp="$dest.partial"
  rm -rf "$tmp"
  # Clone into a temp dir so a failed sparse-checkout never leaves a dest later runs treat as complete.
  if git clone --depth 1 --filter=blob:none --sparse -q https://github.com/apache/poi "$tmp" \
    && git -C "$tmp" sparse-checkout set test-data/slideshow; then
    rm -rf "$dest"
    mv "$tmp" "$dest"
  else
    rm -rf "$tmp"
    exit 1
  fi
fi

count="$(find "$dest/test-data/slideshow" -type f \( -iname '*.pptx' -o -iname '*.potx' -o -iname '*.ppsx' \) | wc -l)"
echo "$count decks in $dest/test-data/slideshow"
