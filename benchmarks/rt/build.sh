#!/usr/bin/env bash
# Build microbench kernels following the method of Øverby & Aksum (2026), §5.2:
# each bench.c is compiled ONCE (rv64gc) to bench.o, then linked into two runtimes:
#   build/htif/<B>.elf  Spike / Rocket / BOOM (libgloss HTIF, printf report)
#   build/xs/<B>.elf    XiangShan in Chipyard (crt_xs, UART-ie report)
# Linker relaxation is disabled so the ROI code is identical in both ELFs (checked below).
# Upstream sources are unmodified; ROI macros come from rt/common.h (shadows upstream).
# Default set = the 23 benchmarks of their Table 5.2.1; ALL=1 builds every benchmark.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MB=$HERE/../microbench
OUT=$HERE/../build
CC=riscv64-unknown-elf-gcc
OBJDUMP=riscv64-unknown-elf-objdump
ARCH="-march=rv64gc -mabi=lp64d -mcmodel=medany -mno-relax"
SUBSET="CCa CCe CCh CCh_st CCl CCm CF1 CRf CS1 CS3 ED1 EM1 EM5 EI EF DP1f DPcvt MC MCS MI MIP ML2_BW_ldst STL2b"
if [ "${ALL:-0}" = 1 ]; then SUBSET=$(cd "$MB" && ls -d */ | tr -d /); fi
mkdir -p "$OUT/obj" "$OUT/htif" "$OUT/xs"
for n in $SUBSET; do
  d=$MB/$n; [ -f "$d/bench.c" ] || { echo "skip $n (no bench.c)"; continue; }
  opt=$(sed -n 's/^OPT=//p' "$d/Makefile" | tail -1); opt=${opt:--O3}
  if [ -f "$d/rand_arr_args.txt" ]; then
    if [ -f "$HERE/randarr/$n.h" ]; then cp "$HERE/randarr/$n.h" "$d/randArr.h"   # frozen input data
    elif [ ! -f "$d/randArr.h" ]; then (cd "$d" && python3 ../rand_c_arr.py $(cat rand_arr_args.txt)); fi
  fi
  # 1) one shared object
  $CC $ARCH $opt -std=c99 -I"$HERE" -I"$MB" -I"$d" -c "$d/bench.c" -o "$OUT/obj/$n.o"
  # 2) link into each runtime
  $CC $ARCH -static -Wl,--no-relax -specs=htif_nano.specs "$HERE/rt_htif.c" "$OUT/obj/$n.o" -lm \
      -o "$OUT/htif/$n.elf"
  $CC $ARCH -static -Wl,--no-relax -nostartfiles -T "$HERE/link_xs.ld" -specs=nano.specs -Wl,--gc-sections \
      "$HERE/crt_xs.S" "$HERE/rt_xs.c" "$OUT/obj/$n.o" -lm -o "$OUT/xs/$n.elf"
  # 3) checks: xs image inside DRAM; ROI kernel identical in both ELFs
  if riscv64-unknown-elf-readelf -lW "$OUT/xs/$n.elf" | awk '/LOAD/{print $3}' | grep -vqE '^0x0*8[0-9a-f]{7}$'; then
    echo "ERROR: $n has a LOAD segment below 0x80000000" >&2; exit 1
  fi
  ops() { $OBJDUMP -d --no-show-raw-insn "$1" | awk '/<loop>:/{f=1;next} f&&/^$/{exit} f{print $2}'; }
  if ! diff -q <(ops "$OUT/htif/$n.elf") <(ops "$OUT/xs/$n.elf") >/dev/null; then
    echo "WARN: $n loop() differs between htif and xs builds" >&2
  fi
  [ -n "$(ops "$OUT/xs/$n.elf")" ] || echo "note: $n has no loop() symbol; identity not checked" >&2
  echo "built $n ($opt)"
done
