#!/bin/bash
# Phase 0 baseline: renders the fixed scenes in scenes.txt and writes the pictures and timings to a folder.
# Needs JDK 25 (JAVA_HOME). Opens a Minecraft window for about four minutes. Run from the project folder:
#   src/test/baseline/run.sh <folder>
# Then compare two folders with:  ./gradlew compareScenes -Pa=<folder> -Pb=<folder>
set -e
cd "$(dirname "$0")/../../.."
out=${1:?usage: run.sh <folder>}
rm -rf "$out" && mkdir -p "$out"
# A fresh world every time: the summoned mobs and items would otherwise pile up.
rm -rf run/saves/PackTest
cp src/test/baseline/scenes.txt "$out/scenes.txt"
JAVA_TOOL_OPTIONS="-Dmetallumextra.debugScript=$PWD/$out/scenes.txt -Dmetallumextra.freezeTime=true $EXTRA_JAVA" ./gradlew runClient > "$out/game.log" 2>&1
grep "debug script: perf" "$out/game.log" | sed 's/.*debug script: /  /'
echo "pictures: $(ls "$out"/*.png | wc -l)"
