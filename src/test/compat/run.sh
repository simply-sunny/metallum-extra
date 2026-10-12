#!/bin/bash
# Compatibility matrix for every shader pack in a folder, in about five minutes:
#   offline  compiles every program of every pack without the game (seconds per pack)
#   game     ONE game session that loads a world once and switches between the packs (about ten seconds each), taking a picture of each
#   all      both, then prints the table
# Needs JDK 25 (JAVA_HOME). Usage: src/test/compat/run.sh [offline|game|all] [pack folder]
set -u
cd "$(dirname "$0")/../../.."
MODE=${1:-all}
SRC=${2:-"$HOME/Library/Application Support/Polyfrost/OneClient/clusters/Metallically Vibrant/shaderpacks"}
OUT=build/compat
mkdir -p "$OUT/shots"
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$HOME/Library/Application Support/Polyfrost/OneClient/metadata/java/microsoft-jdk-25.0.4.1-macos-aarch64/jdk-25.0.4.1+1/Contents/Home"
fi

# Safe names for the packs (the game's debug script takes plain file names).
: > "$OUT/packs.txt"
i=0
for f in "$SRC"/*.zip; do
  [ -e "$f" ] || continue
  i=$((i + 1))
  link=$(printf "zz_%02d.zip" "$i")
  ln -sfn "$(readlink "$f" || echo "$f")" "run/shaderpacks/$link"
  echo "$link|$(basename "$f" .zip)" >> "$OUT/packs.txt"
done
cleanup() { rm -f run/shaderpacks/zz_*.zip; }
trap cleanup EXIT

if [ "$MODE" = offline ] || [ "$MODE" = all ]; then
  : > "$OUT/offline.txt"
  ./gradlew -I local-testing.init.gradle testClasses -q 2>/dev/null
  while IFS='|' read -r link name; do
    result=$(./gradlew -I local-testing.init.gradle compileCheck "-Pzip=run/shaderpacks/$link" --console=plain -q 2>&1 \
      | grep -E "programs,|PackException|Exception in|declares the sampler" | sed -E 's/^Exception in thread "main" com\.metallumextra\.shader\.pack\.PackException: //' | head -1 | cut -c1-200)
    echo "$link|$name|${result:-no result}" >> "$OUT/offline.txt"
  done < "$OUT/packs.txt"
fi

if [ "$MODE" = game ] || [ "$MODE" = all ]; then
  {
    echo "world Test"; echo "wait 8000"
    echo "cmd gamemode survival @p"; echo "cmd weather clear"; echo "cmd time set 5000"
    echo "cmd spreadplayers -540 70 1 40 false @p"; echo "wait 5000"
    echo "cmd item replace entity @p weapon.mainhand with minecraft:diamond_sword"
    echo "set shaders off"; echo "wait 1500"; echo "perf 2000 vanilla"
    while IFS='|' read -r link name; do
      echo "pack $link"; echo "set shaders on"; echo "wait 6000"; echo "perf 2000 $link"; echo "shot $link"
    done < "$OUT/packs.txt"
    echo "quit"
  } > "$OUT/script.txt"
  sed -i '' 's/^shaders.pack=.*/shaders.pack=builtin/' run/config/metallum-extra.properties 2>/dev/null
  JAVA_TOOL_OPTIONS="-Dmetallumextra.debugScript=$PWD/$OUT/script.txt" timeout 900 ./gradlew runClient --console=plain > "$OUT/game.log" 2>&1
  echo "exit $?" >> "$OUT/game.log"
  mv "$OUT"/zz_*.png "$OUT/shots/" 2>/dev/null; mv "$OUT"/zz_*.txt "$OUT/shots/" 2>/dev/null
fi

python3 -I src/test/compat/report.py "$OUT"
