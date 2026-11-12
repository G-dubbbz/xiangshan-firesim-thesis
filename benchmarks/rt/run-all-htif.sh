#!/usr/bin/env bash
# Run benchmarks on a Chipyard Verilator simulator (Rocket/BOOM), J in parallel.
# Same flags as the XiangShan runs except: no +init_write (tohost lives in DRAM via the
# libgloss HTIF linker script) and no +verbose (on Rocket/BOOM it only enables commit-trace
# printfs, which slow the simulation without changing target timing).
#
# Usage: CONFIG=MediumBoomV3Config [BENCHES="a b"] [J=6] [MIN_CYCLES=60000000] \
#        [SKIP_PASSED=1] bash rt/run-all-htif.sh
#   default        every build/htif/*.elf, longest first (Spike instret + .bss size)
#   BENCHES        run only these
#   SKIP_PASSED=1  (default) skip a benchmark whose last run passed with the same ELF
#                  (sha256 recorded in results/<CONFIG>/<B>.elf.sha256); 0 = rerun everything
#   MIN_CYCLES     cycle limit for small benchmarks; limit = max(MIN_CYCLES, 24 * Spike
#                  ROI instret) harness cycles. Passing runs stop on their own.
set -uo pipefail
: "${CONFIG:?set CONFIG}"
B=$(cd "$(dirname "$0")/.." && pwd)
SIM=${SIM:-/work/chipyard/sims/verilator/simulator-chipyard.harness-$CONFIG}
OUT=$B/results/$CONFIG
J=${J:-6}
MIN_CYCLES=${MIN_CYCLES:-60000000}
SKIP_PASSED=${SKIP_PASSED:-1}
REF=$B/spike-results.txt
[ -x "$SIM" ] || { echo "no simulator $SIM" >&2; exit 1; }
[ -f "$REF" ] || { echo "no $REF: run rt/run-spike.sh first" >&2; exit 1; }
mkdir -p "$OUT"
{ echo "== run started $(date -Is)"; echo "config $CONFIG"; echo "chipyard $(git -C /work/chipyard rev-parse HEAD)"
  echo "thesis   $(git -C "$B/.." rev-parse HEAD)"; echo "microbench $(cat "$B/microbench-commit.txt")"
  ls -l "$SIM"; } >> "$OUT/meta.txt"

spike_instret() { awk -v n="$1" '$1==n{sub("instret=","",$4); print $4}' "$REF"; }
passed() {   # same pass rule as compare.py: a ROI report in the log
  [ -f "$OUT/$1.log" ] && grep -q 'ROI cycles=' "$OUT/$1.log" && ! grep -q FAILED "$OUT/$1.log"
}
cost() {
  local ins bss
  ins=$(spike_instret "$1")
  bss=$(riscv64-unknown-elf-size -A "$B/build/htif/$1.elf" 2>/dev/null | awk '$1==".bss"||$1==".sbss"{s+=$2} END{print s+0}')
  echo $(( ${ins:-0} + ${bss:-0} ))
}
run_one() {
  n=$1; elf=$B/build/htif/$n.elf
  ins=$(spike_instret "$n")
  [ -n "$ins" ] || echo "warning: $n not in spike-results.txt, using MIN_CYCLES" >&2
  lim=$(( 24 * ${ins:-0} )); [ "$lim" -lt "$MIN_CYCLES" ] && lim=$MIN_CYCLES
  [ "$lim" -gt 2000000000 ] && lim=2000000000     # +max-cycles is a 32-bit integer in TestDriver
  sha256sum < "$elf" | cut -d' ' -f1 > "$OUT/$n.elf.sha256"
  start=$(date +%s)
  "$SIM" +permissive +dramsim +dramsim_ini_dir=/work/chipyard/generators/testchipip/src/main/resources/dramsim2_ini \
    +max-cycles=$lim +loadmem="$elf" +permissive-off "$elf" </dev/null >"$OUT/$n.log" 2>"$OUT/$n.out"
  rc=$?
  st=FAIL; passed "$n" && st=pass
  echo "done $n  $st  rc=$rc  $(( $(date +%s) - start ))s  limit=$lim"
}
export -f run_one passed spike_instret; export B SIM OUT REF MIN_CYCLES

if [ -n "${BENCHES:-}" ]; then list=$BENCHES
else list=$(ls "$B"/build/htif/*.elf | xargs -n1 basename | sed 's/\.elf$//'); fi
todo=()
for n in $list; do
  [ -f "$B/build/htif/$n.elf" ] || { echo "skip $n: no build/htif/$n.elf" >&2; continue; }
  if [ "$SKIP_PASSED" = 1 ] && passed "$n" && [ -f "$OUT/$n.elf.sha256" ] &&
     [ "$(sha256sum < "$B/build/htif/$n.elf" | cut -d' ' -f1)" = "$(cat "$OUT/$n.elf.sha256")" ]; then
    echo "skip $n: already passed with this ELF" >&2; continue
  fi
  todo+=("$n")
done
[ ${#todo[@]} -gt 0 ] || { echo "nothing to run"; exit 0; }
echo "running ${#todo[@]} benchmark(s) on $CONFIG, J=$J, MIN_CYCLES=$MIN_CYCLES"
for n in "${todo[@]}"; do echo "$(cost "$n") $n"; done | sort -rn | awk '{print $2}' | \
  xargs -P "$J" -I{} bash -c 'run_one {}' | tee -a "$OUT/runs.txt"

nf=0; for n in "${todo[@]}"; do passed "$n" || { echo "FAILED: $n" >&2; nf=$((nf+1)); }; done
echo "$(( ${#todo[@]} - nf ))/${#todo[@]} passed this run"
