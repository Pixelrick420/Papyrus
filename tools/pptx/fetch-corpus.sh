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
  git clone --depth 1 --filter=blob:none --sparse -q https://github.com/apache/poi "$dest"
  git -C "$dest" sparse-checkout set test-data/slideshow
fi

count="$(find "$dest/test-data/slideshow" -type f \( -iname '*.pptx' -o -iname '*.potx' -o -iname '*.ppsx' \) | wc -l)"
echo "$count decks in $dest/test-data/slideshow"
