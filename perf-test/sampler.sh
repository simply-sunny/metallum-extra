#!/bin/bash
# sampler.sh PID OUTFILE: once a second, what the machine and the game process are using (GPU utilisation and memory from the driver, the process's CPU and resident memory, load).
PID="$1"; OUT="$2"
echo "wall_s,gpu_device_pct,gpu_renderer_pct,gpu_tiler_pct,gpu_alloc_mb,gpu_in_use_mb,proc_cpu_pct,proc_rss_mb,proc_threads,load1,cpu_limit_pct" > "$OUT"
START=$(date +%s)
N=0
while kill -0 "$PID" 2>/dev/null; do
  S=$(ioreg -r -d 1 -w0 -c IOAccelerator | grep -o '"PerformanceStatistics" = {[^}]*}' | head -1)
  g() { echo "$S" | grep -o "\"$1\"=[0-9]*" | head -1 | cut -d= -f2; }
  P=$(ps -o %cpu=,rss=,wq= -p "$PID" 2>/dev/null)
  TH=$(ps -M -p "$PID" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')
  L=$(sysctl -n vm.loadavg | awk '{print $2}')
  LIM=""
  if [ $((N % 5)) -eq 0 ]; then LIM=$(pmset -g therm 2>/dev/null | grep -o "CPU_Speed_Limit.*" | grep -o "[0-9]*" | head -1); fi
  echo "$(( $(date +%s) - START )),$(g 'Device Utilization %'),$(g 'Renderer Utilization %'),$(g 'Tiler Utilization %'),$(( $(g 'Alloc system memory') / 1048576 )),$(( $(g 'In use system memory') / 1048576 )),$(echo $P | awk '{print $1}'),$(( $(echo $P | awk '{print $2}') / 1024 )),$TH,$L,$LIM" >> "$OUT"
  N=$((N+1)); sleep 1
done
