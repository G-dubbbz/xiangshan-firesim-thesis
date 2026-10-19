#!/usr/bin/env bash
# Run every build/xs/*.elf on XiangShan in direct Chipyard Verilator, J in parallel.
# Calls the simulator directly (same flags as 'make run-binary LOADMEM=1' + run-xs.sh)
# to avoid concurrent make invocations. Cycle limit = 4*spike_instret + 300k.
set -uo pipefail
B=$(cd "$(dirname "$0")/.." && pwd)
SIMDIR=/work/chipyard/sims/verilator
SIM=$SIMDIR/simulator-chipyard.harness-XiangShanConfig
OUT=$B/results/xs
J=${J:-8}
mkdir -p "$OUT"
{ date -Is; echo "chipyard $(git -C /work/chipyard rev-parse HEAD)"
  echo "thesis   $(git -C "$B/.." rev-parse HEAD)"; echo "microbench $(cat "$B/microbench-commit.txt")"
  ls -l "$SIM"; } > "$OUT/meta.txt"
run_one() {
  n=$1; elf=$B/build/xs/$n.elf
  ins=$(awk -v n="$n" '$1==n{sub("instret=","",$4); print $4}' "$B/spike-results.txt")
  to=$((4*ins + 300000))
  start=$(date +%s)
  "$SIM" +permissive +dramsim +dramsim_ini_dir=/work/chipyard/generators/testchipip/src/main/resources/dramsim2_ini \
    +max-cycles=$to +init_write=0x08000000:0x0 +init_write=0x8000004:0x0 \
    +init_write=0x08000040:0x0 +init_write=0x8000044:0x0 +loadmem="$elf" \
    +verbose +permissive-off "$elf" </dev/null >"$OUT/$n.log" 2>"$OUT/$n.out"
  echo "done $n  rc=$?  $(( $(date +%s) - start ))s  limit=$to"
}
export -f run_one; export B SIM OUT
ls "$B"/build/xs/*.elf | xargs -n1 basename | sed 's/\.elf$//' | \
  xargs -P "$J" -I{} bash -c 'run_one {}' | tee "$OUT/runs.txt"
