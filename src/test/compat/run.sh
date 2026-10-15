#!/bin/bash
# Shader pack compatibility matrix in about five minutes for ~15 packs:
#   offline  every program of every pack compiled in ONE JVM (seconds per pack, no game)
#   game     ONE game session: world loaded once, packs switched in turn (~10 s each), a frame-time sample and a picture per pack
#   all      both, then the table
# Usage: src/test/compat/run.sh [offline|game|all] [pack folder]   (needs JDK 25 in JAVA_HOME; packs default to the 26.3 instance's shaderpacks)
set -u
cd "$(dirname "$0")/../../.."
MODE=${1:-all}
SRC=${2:-"$HOME/Library/Application Support/Polyfrost/OneClient/.minecraft/shaderpacks"}
OUT=build/compat
mkdir -p "$OUT/shots" run/shaderpacks
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$HOME/Library/Application Support/Polyfrost/OneClient/metadata/java/microsoft-jdk-25.0.4.1-macos-aarch64/jdk-25.0.4.1+1/Contents/Home"
fi
GRADLE=(./gradlew -I local-testing.init.gradle --console=plain -q)

# Plain names for the packs: the debug script takes bare file names.
: > "$OUT/packs.txt"
i=0
for f in "$SRC"/*.zip; do
  [ -e "$f" ] || continue
  i=$((i + 1))
  link=$(printf "zz_%02d.zip" "$i")
  ln -sfn "$(readlink "$f" || echo "$f")" "run/shaderpacks/$link"
  echo "$link|$(basename "$f" .zip)" >> "$OUT/packs.txt"
done
trap 'rm -f run/shaderpacks/zz_*.zip' EXIT

if [ "$MODE" = offline ] || [ "$MODE" = all ]; then
  "${GRADLE[@]}" compileCheckAll -Pfolder=run/shaderpacks 2>&1 | grep -E '^zz_[0-9]+\.zip\|' > "$OUT/offline.txt"
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
  mv run/zz_*.png "$OUT/shots/" 2>/dev/null
fi

python3 -I src/test/compat/report.py "$OUT"
