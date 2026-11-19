#!/usr/bin/env bash
# Regenerates oracle.json from Apache POI's preset geometry.
# Standalone: needs only a JDK and network access on first run.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
version="5.2.5"
cache="${XDG_CACHE_HOME:-$HOME/.cache}/papyrus-poi/$version"
base="https://repo1.maven.org/maven2"

jars=(
  "org/apache/poi/poi/$version/poi-$version.jar"
  "org/apache/poi/poi-ooxml/$version/poi-ooxml-$version.jar"
  "org/apache/poi/poi-ooxml-lite/$version/poi-ooxml-lite-$version.jar"
  "org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar"
  "org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar"
  "org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar"
  "commons-io/commons-io/2.15.1/commons-io-2.15.1.jar"
  "org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar"
  "org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar"
  "com/zaxxer/SparseBitSet/1.3/SparseBitSet-1.3.jar"
)

mkdir -p "$cache"
classpath=""
for jar in "${jars[@]}"; do
  file="$cache/$(basename "$jar")"
  if [ ! -f "$file" ]; then
    echo "downloading $(basename "$jar")" >&2
    curl -fsSL "$base/$jar" -o "$file"
  fi
  classpath="$classpath:$file"
done
classpath="${classpath#:}"

rm -rf "$here/build"
mkdir -p "$here/build"
javac -cp "$classpath" -d "$here/build" "$here/OracleDump.java"
java -cp "$here/build:$classpath" OracleDump "${1:-$here/oracle.json}"
