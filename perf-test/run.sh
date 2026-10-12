#!/bin/bash
# run.sh a|b: one measured run, alone on the machine. A fresh copy of the prepared world is used each time.
set -e
cd "$(dirname "$0")"
W="$1"; SCRIPT="${2:-$1}"; INST="$PWD/inst-$W"; OUT="$PWD/results/$SCRIPT"
if pgrep -f "[K]notClient" >/dev/null; then echo "another game is running; not starting"; exit 1; fi
rm -rf "$INST/saves/PerfWorld" "$OUT"; mkdir -p "$OUT"; cp -R world-template "$INST/saves/PerfWorld"
for i in $(seq 1 30); do
  GPU=$(ioreg -r -d 1 -w0 -c IOAccelerator | grep -o '"Device Utilization %"=[0-9]*' | cut -d= -f2)
  [ "${GPU:-0}" -lt 8 ] && break
  sleep 2
done
GPU=$(ioreg -r -d 1 -w0 -c IOAccelerator | grep -o '"Device Utilization %"=[0-9]*' | cut -d= -f2)
echo "GPU busy before start: ${GPU}%; load: $(sysctl -n vm.loadavg)" | tee "$OUT/preflight.txt"
./launch.sh "$INST" "$PWD/scripts/$SCRIPT.txt" "$OUT" > "$OUT/game.log" 2>&1 &
sleep 3
GAME=$(pgrep -f "[K]notClient" | head -1)
./sampler.sh "$GAME" "$OUT/system.csv" &
SAMPLER=$!
wait "$GAME" 2>/dev/null || while kill -0 "$GAME" 2>/dev/null; do sleep 2; done
kill "$SAMPLER" 2>/dev/null || true
echo finished
