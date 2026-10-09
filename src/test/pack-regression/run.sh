#!/bin/bash
# Starts the game, switches between the RedToBlue and BlueToRed test packs, and checks the saved pictures.
# Needs JDK 25 (JAVA_HOME). Opens a Minecraft window for about a minute. Run from the project folder:
#   src/test/pack-regression/run.sh [keep-cache]
# The shader translation cache is emptied first, so the counters in the log start from nothing; "keep-cache" leaves
# it, to see a restart reuse it (then "MSL translations" in the log should stay at 0).
set -e
cd "$(dirname "$0")/../../.."
out=run/pack-regression
rm -rf "$out" && mkdir -p "$out"
[ "$1" = keep-cache ] || rm -rf run/cache
./gradlew writeTestPacks -Pto=run/shaderpacks -q
cp src/test/pack-regression/packs.txt "$out/packs.txt"
JAVA_TOOL_OPTIONS="-Dmetallumextra.debugScript=$PWD/$out/packs.txt" ./gradlew runClient > "$out/game.log" 2>&1
grep "debug script: MSL cache" "$out/game.log" | sed 's/.*debug script: /  /'
./gradlew checkPackImages -Pfolder="$out" -q
