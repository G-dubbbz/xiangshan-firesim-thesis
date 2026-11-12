#!/usr/bin/env bash
# Run benchmarks on XiangShan in direct Chipyard Verilator, J in parallel.
# Calls the simulator directly (same flags as 'make run-binary LOADMEM=1' + run-xs.sh)
# to avoid concurrent make invocations.
#
# Usage: [BENCHES="a b"] [J=8] [MIN_CYCLES=60000000] [SKIP_PASSED=1] bash rt/run-all-xs.sh
#   default        every build/xs/*.elf, longest first (Spike instret + .bss size)
#   BENCHES        run only these
#   SKIP_PASSED=1  (default) skip a benchmark whose last run passed with the same ELF
#                  (sha256 recorded in results/xs/<B>.elf.sha256); 0 = rerun everything
#   MIN_CYCLES     cycle limit for small benchmarks. The limit is
#                  max(MIN_CYCLES, 24 * Spike ROI instret) harness cycles. The harness
#                  clock runs faster than XiangShan's core clock, so this leaves room for
#                  low-IPC kernels and for startup (zeroing .bss, malloc). Passing runs
#                  stop on their own, so a high limit only costs time if a run hangs.
#   SIM            simulator binary (default: the XiangShanConfig Verilator simulator)
set -uo pipefail
B=$(cd "$(dirname "$0")/.." && pwd)
SIMDIR=/work/chipyard/sims/verilator
SIM=${SIM:-$SIMDIR/simulator-chipyard.harness-XiangShanConfig}
OUT=$B/results/xs
J=${J:-8}
MIN_CYCLES=${MIN_CYCLES:-60000000}
SKIP_PASSED=${SKIP_PASSED:-1}
REF=$B/spike-results.txt
[ -x "$SIM" ] || { echo "no simulator $SIM" >&2; exit 1; }
[ -f "$REF" ] || { echo "no $REF: run rt/run-spike.sh first" >&2; exit 1; }
mkdir -p "$OUT"
{ echo "== run started $(date -Is)"; echo "chipyard $(git -C /work/chipyard rev-parse HEAD)"
  echo "thesis   $(git -C "$B/.." rev-parse HEAD)"; echo "microbench $(cat "$B/microbench-commit.txt")"
  ls -l "$SIM"; } >> "$OUT/meta.txt"

spike_instret() { awk -v n="$1" '$1==n{sub("instret=","",$4); print $4}' "$REF"; }
passed() {   # same pass rule as parse_xs.py / compare.py
  [ -f "$OUT/$1.log" ] && grep -q 'TestDriver.v:158' "$OUT/$1.log" && ! grep -q FAILED "$OUT/$1.log"
}
cost() {
  local ins bss
  ins=$(spike_instret "$1")
  bss=$(riscv64-unknown-elf-size -A "$B/build/xs/$1.elf" 2>/dev/null | awk '$1==".bss"||$1==".sbss"{s+=$2} END{print s+0}')
  echo $(( ${ins:-0} + ${bss:-0} ))
}
run_one() {
  n=$1; elf=$B/build/xs/$n.elf
  ins=$(spike_instret "$n")
  [ -n "$ins" ] || echo "warning: $n not in spike-results.txt, using MIN_CYCLES" >&2
  lim=$(( 24 * ${ins:-0} )); [ "$lim" -lt "$MIN_CYCLES" ] && lim=$MIN_CYCLES
  [ "$lim" -gt 2000000000 ] && lim=2000000000     # +max-cycles is a 32-bit integer in TestDriver
  sha256sum < "$elf" | cut -d' ' -f1 > "$OUT/$n.elf.sha256"
  start=$(date +%s)
  "$SIM" +permissive +dramsim +dramsim_ini_dir=/work/chipyard/generators/testchipip/src/main/resources/dramsim2_ini \
    +max-cycles=$lim +init_write=0x08000000:0x0 +init_write=0x8000004:0x0 \
    +init_write=0x08000040:0x0 +init_write=0x8000044:0x0 +loadmem="$elf" \
    +verbose +permissive-off "$elf" </dev/null >"$OUT/$n.log" 2>"$OUT/$n.out"
  rc=$?
  st=FAIL; passed "$n" && st=pass
  echo "done $n  $st  rc=$rc  $(( $(date +%s) - start ))s  limit=$lim"
}
export -f run_one passed spike_instret; export B SIM OUT REF MIN_CYCLES

if [ -n "${BENCHES:-}" ]; then list=$BENCHES
else list=$(ls "$B"/build/xs/*.elf | xargs -n1 basename | sed 's/\.elf$//'); fi
todo=()
for n in $list; do
  [ -f "$B/build/xs/$n.elf" ] || { echo "skip $n: no build/xs/$n.elf" >&2; continue; }
  if [ "$SKIP_PASSED" = 1 ] && passed "$n" && [ -f "$OUT/$n.elf.sha256" ] &&
     [ "$(sha256sum < "$B/build/xs/$n.elf" | cut -d' ' -f1)" = "$(cat "$OUT/$n.elf.sha256")" ]; then
    echo "skip $n: already passed with this ELF" >&2; continue
  fi
  todo+=("$n")
done
[ ${#todo[@]} -gt 0 ] || { echo "nothing to run"; exit 0; }
echo "running ${#todo[@]} benchmark(s), J=$J, MIN_CYCLES=$MIN_CYCLES"
for n in "${todo[@]}"; do echo "$(cost "$n") $n"; done | sort -rn | awk '{print $2}' | \
  xargs -P "$J" -I{} bash -c 'run_one {}' | tee -a "$OUT/runs.txt"

nf=0; for n in "${todo[@]}"; do passed "$n" || { echo "FAILED: $n" >&2; nf=$((nf+1)); }; done
echo "$(( ${#todo[@]} - nf ))/${#todo[@]} passed this run"
