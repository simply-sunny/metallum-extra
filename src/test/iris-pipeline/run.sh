#!/bin/bash
# Plays the multi-pass pipeline test in the real game, with Metal's validation layer on, then checks the log and the pictures.
# Needs JDK 25 (JAVA_HOME). Opens a Minecraft window for a few minutes. Run from the project folder:
#   src/test/iris-pipeline/run.sh
set -e
cd "$(dirname "$0")/../../.."
out=run/iris-pipeline
rm -rf "$out" && mkdir -p "$out"
rm -rf run/saves/PackTest
./gradlew writeTestPacks -Pto=run/shaderpacks -q
cp src/test/iris-pipeline/iris.txt "$out/iris.txt"
MTL_DEBUG_LAYER=1 METAL_DEVICE_WRAPPER_TYPE=1 JAVA_TOOL_OPTIONS="-Dmetallumextra.debugScript=$PWD/$out/iris.txt" ./gradlew runClient > "$out/game.log" 2>&1
./gradlew checkIris -Pfolder="$out" -q
