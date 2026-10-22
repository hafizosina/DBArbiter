#!/usr/bin/env bash
# Builds a Windows x64 version of DBArbiter on Linux:
#   target/windows/DBArbiter/DBArbiter.exe + jre/   (unzip and double-click; no Java install needed)
#   target/windows/DBArbiter-windows-x64.zip
# The Temurin JRE download is cached in ~/.cache/dbadmin.
set -euo pipefail
cd "$(dirname "$0")/.."

JRE_FEATURE=21
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/dbadmin"
JRE_ZIP="$CACHE/temurin-$JRE_FEATURE-jre-windows-x64.zip"
OUT=target/windows/DBArbiter

# no "clean": target/Script holds scripts saved by the jar version of the app
rm -rf target/windows
# no tests here: with the Windows JavaFX jars the UI tests cannot start on Linux (the normal build runs them)
mvn -B -q -Pwindows -Djavafx.platform=win -DskipTests package

if [ ! -f "$JRE_ZIP" ]; then
    echo "Downloading Temurin $JRE_FEATURE JRE for Windows x64..."
    mkdir -p "$CACHE"
    curl -fsSL -o "$JRE_ZIP.part" \
        "https://api.adoptium.net/v3/binary/latest/$JRE_FEATURE/ga/windows/x64/jre/hotspot/normal/eclipse"
    mv "$JRE_ZIP.part" "$JRE_ZIP"
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -q "$JRE_ZIP" -d "$TMP"
mv "$TMP"/*/ "$OUT/jre"

(cd target/windows && zip -qr DBArbiter-windows-x64.zip DBArbiter)
echo "Built $(pwd)/target/windows/DBArbiter-windows-x64.zip ($(du -h target/windows/DBArbiter-windows-x64.zip | cut -f1))"
