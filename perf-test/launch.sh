#!/bin/bash
# launch.sh INSTANCE SCRIPT OUTDIR [extra java args]: starts the game directly (not through OneClient) in that instance and runs the script.
set -e
INST="$1"; SCRIPT="$2"; OUT="$3"; shift 3
ONE="$HOME/Library/Application Support/Polyfrost/OneClient"
JAVA="$ONE/metadata/java/microsoft-jdk-25.0.4.1-macos-aarch64/jdk-25.0.4.1+1/Contents/Home/bin/java"
CP="$(cat "$(dirname "$0")/classpath.txt")"
cd "$INST"
exec "$JAVA" -XstartOnFirstThread --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  "-Djava.library.path=$ONE/metadata/natives/26.2-0.19.5/java" "-Djna.tmpdir=$ONE/metadata/natives/26.2-0.19.5/jna" \
  "-Dorg.lwjgl.system.SharedLibraryExtractPath=$ONE/metadata/natives/26.2-0.19.5/lwjgl" "-Dio.netty.native.workdir=$ONE/metadata/natives/26.2-0.19.5/netty" \
  -DFabricMcEmu=net.minecraft.client.main.Main -Xms2048M -Xmx6144M --add-modules=jdk.incubator.vector -XX:+UseG1GC \
  "-Dfabric.modsFolder=$INST/mods" "-Dperfbench.script=$SCRIPT" "-Dperfbench.out=$OUT" "$@" \
  -cp "$CP" net.fabricmc.loader.impl.launch.knot.KnotClient \
  --username PerfBench --version 26.2 --gameDir "$INST" --assetsDir "$ONE/metadata/assets" --assetIndex 32 \
  --uuid 00000000000000000000000000000001 --accessToken 0 --versionType release --width 1280 --height 800
