#!/usr/bin/env bash
# Spike reference run: ROI cycles/instret of every build/htif/*.elf, written to
# spike-results.txt. These instret values are the reference every core must match exactly.
# Usage: [BENCHES="a b"] bash rt/run-spike.sh
#   default         all built benchmarks; spike-results.txt is rewritten
#   BENCHES="a b"   only these; their lines in spike-results.txt are replaced, others kept
# Afterwards it prints the largest ROIs and a rough XiangShan Verilator time estimate.
set -uo pipefail
B=$(cd "$(dirname "$0")/.." && pwd)
REF=$B/spike-results.txt
if [ -n "${BENCHES:-}" ]; then list=$BENCHES
else list=$(ls "$B"/build/htif/*.elf | xargs -n1 basename | sed 's/\.elf$//'); fi
new=$(mktemp)
for n in $list; do
  printf '%-12s ' "$n"
  timeout --foreground 600 spike --isa=rv64gc "$B/build/htif/$n.elf" </dev/null | grep ROI || echo "FAIL/timeout"
done | tee "$new"

if [ -n "${BENCHES:-}" ] && [ -f "$REF" ]; then
  pat="^($(echo $list | tr ' ' '|'))[[:space:]]"
  { grep -vE "$pat" "$REF"; cat "$new"; } | LC_ALL=C sort -k1,1 > "$REF.tmp" && mv "$REF.tmp" "$REF"
else
  LC_ALL=C sort -k1,1 "$new" > "$REF"
fi
rm -f "$new"
echo "wrote $REF ($(grep -c ROI "$REF") benchmarks with a ROI report)"
grep -q 'FAIL' "$REF" && echo "WARNING: some benchmarks failed on Spike:" && grep FAIL "$REF"

# Rough planning estimate for XiangShan in direct Verilator: ~1 cycle per ROI instruction,
# ~0.8 cycles per byte of .bss zeroed at startup, ~1,300 cycles per second. Heap set up with
# malloc before the ROI (MM, MM_st, MD, ML2*, STL2) is not counted. Only a guide for planning.
echo
echo "Largest benchmarks (rough XiangShan Verilator estimate):"
printf '  %-12s %12s %12s %8s\n' bench roi_instret bss_bytes est_h
for n in $(awk '/ROI/{print $1}' "$REF"); do
  ins=$(awk -v n="$n" '$1==n{sub("instret=","",$4); print $4}' "$REF")
  bss=$(riscv64-unknown-elf-size -A "$B/build/xs/$n.elf" 2>/dev/null | awk '$1==".bss"||$1==".sbss"{s+=$2} END{print s+0}')
  echo "$n $ins $bss"
done | awk '{h=($2 + 0.8*$3)/1300/3600; printf "  %-12s %12d %12d %8.1f\n", $1, $2, $3, h}' | sort -k4,4 -rn | head -10
