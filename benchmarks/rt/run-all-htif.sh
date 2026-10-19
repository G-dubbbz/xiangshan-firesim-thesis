#!/usr/bin/env bash
# Run every build/htif/*.elf on a Chipyard Verilator simulator (Rocket/BOOM), J in parallel.
# Usage: CONFIG=MediumBoomV3Config [J=6] bash rt/run-all-htif.sh
# Same flags as the XiangShan runs except: no +init_write (tohost lives in DRAM via the
# libgloss HTIF linker script) and no +verbose (on Rocket/BOOM it only enables commit-trace
# printfs, which slow the simulation without changing target timing).
set -uo pipefail
: "${CONFIG:?set CONFIG}"
B=$(cd "$(dirname "$0")/.." && pwd)
SIM=/work/chipyard/sims/verilator/simulator-chipyard.harness-$CONFIG
OUT=$B/results/$CONFIG
J=${J:-6}
[ -x "$SIM" ] || { echo "no simulator $SIM" >&2; exit 1; }
mkdir -p "$OUT"
{ date -Is; echo "config $CONFIG"; echo "chipyard $(git -C /work/chipyard rev-parse HEAD)"
  echo "thesis   $(git -C "$B/.." rev-parse HEAD)"; echo "microbench $(cat "$B/microbench-commit.txt")"
  ls -l "$SIM"; } > "$OUT/meta.txt"
run_one() {
  n=$1; elf=$B/build/htif/$n.elf; start=$(date +%s)
  "$SIM" +permissive +dramsim +dramsim_ini_dir=/work/chipyard/generators/testchipip/src/main/resources/dramsim2_ini \
    +max-cycles=8000000 +loadmem="$elf" +permissive-off "$elf" </dev/null >"$OUT/$n.log" 2>"$OUT/$n.out"
  echo "done $n  rc=$?  $(( $(date +%s) - start ))s"
}
export -f run_one; export B SIM OUT
ls "$B"/build/htif/*.elf | xargs -n1 basename | sed 's/\.elf$//' | \
  xargs -P "$J" -I{} bash -c 'run_one {}' | tee "$OUT/runs.txt"
