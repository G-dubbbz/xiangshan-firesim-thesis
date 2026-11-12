#!/usr/bin/env bash
# Build microbench kernels following the method of Øverby & Aksum (2026), §5.2:
# each bench.c is compiled ONCE (rv64gc) to bench.o, then linked into two runtimes:
#   build/htif/<B>.elf  Spike / Rocket / BOOM (libgloss HTIF, printf report)
#   build/xs/<B>.elf    XiangShan in Chipyard (crt_xs, UART-ie report)
# Linker relaxation is disabled so the ROI code is identical in both ELFs (checked below).
# Upstream sources are unmodified; ROI macros come from rt/common.h (shadows upstream).
#
# Which benchmarks:
#   default          every benchmark directory in microbench/ (40 at the pinned commit)
#   SET=23           the 23 benchmarks of Øverby & Aksum's Table 5.2.1
#   BENCHES="a b"    exactly these
#
# Random input data: benchmarks with rand_arr_args.txt get their data from rt/randarr/<B>.h.
# If no frozen copy exists yet, the data is generated once and frozen into rt/randarr/
# (commit the new file and SHA256SUMS), so every later build and every core uses it.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
MB=$HERE/../microbench
OUT=$HERE/../build
CC=riscv64-unknown-elf-gcc
OBJDUMP=riscv64-unknown-elf-objdump
NM=riscv64-unknown-elf-nm
READELF=riscv64-unknown-elf-readelf
ARCH="-march=rv64gc -mabi=lp64d -mcmodel=medany -mno-relax"
SUBSET23="CCa CCe CCh CCh_st CCl CCm CF1 CRf CS1 CS3 ED1 EM1 EM5 EI EF DP1f DPcvt MC MCS MI MIP ML2_BW_ldst STL2b"
if [ -n "${BENCHES:-}" ]; then SUBSET=$BENCHES
elif [ "${SET:-all}" = 23 ]; then SUBSET=$SUBSET23
else SUBSET=$(cd "$MB" && for d in */; do [ -f "$d/bench.c" ] && echo "${d%/}"; done); fi
mkdir -p "$OUT/obj" "$OUT/htif" "$OUT/xs"

# Instruction sequence (mnemonics) of function $2 in ELF $1.
ops() { $OBJDUMP -d --no-show-raw-insn "$1" | awk -v h="<$2>:" '$2==h{f=1;next} f&&/^$/{exit} f{print $2}'; }

build_one() {
  local n=$1 d=$MB/$1 opt fn
  [ -f "$d/bench.c" ] || { echo "skip $n (no bench.c)"; return 0; }
  opt=$(sed -n 's/^OPT=//p' "$d/Makefile" | tail -1); opt=${opt:--O3}
  if [ -f "$d/rand_arr_args.txt" ]; then
    if [ -f "$HERE/randarr/$n.h" ]; then
      cp "$HERE/randarr/$n.h" "$d/randArr.h"                         # frozen input data
    else
      [ -f "$d/randArr.h" ] || (cd "$d" && python3 ../rand_c_arr.py $(cat rand_arr_args.txt)) || return 1
      mkdir -p "$HERE/randarr"; cp "$d/randArr.h" "$HERE/randarr/$n.h"   # freeze it now
      (cd "$MB" && sha256sum "$n/randArr.h") >> "$HERE/randarr/SHA256SUMS"
      echo "FROZE new random input data: rt/randarr/$n.h (commit it with SHA256SUMS)" >&2
    fi
  fi
  # 1) one shared object
  $CC $ARCH $opt -std=c99 -I"$HERE" -I"$MB" -I"$d" -c "$d/bench.c" -o "$OUT/obj/$n.o" || return 1
  # 2) link into each runtime
  $CC $ARCH -static -Wl,--no-relax -specs=htif_nano.specs "$HERE/rt_htif.c" "$OUT/obj/$n.o" -lm \
      -o "$OUT/htif/$n.elf" || return 1
  $CC $ARCH -static -Wl,--no-relax -nostartfiles -T "$HERE/link_xs.ld" -specs=nano.specs -Wl,--gc-sections \
      "$HERE/crt_xs.S" "$HERE/rt_xs.c" "$OUT/obj/$n.o" -lm -o "$OUT/xs/$n.elf" || return 1
  # 3) checks: xs image inside DRAM; every function of the benchmark identical in both ELFs
  if $READELF -lW "$OUT/xs/$n.elf" | awk '/LOAD/{print $3}' | grep -vqE '^0x0*8[0-9a-f]{7}$'; then
    echo "ERROR: $n has a LOAD segment below 0x80000000" >&2; return 1
  fi
  for fn in $($NM --defined-only "$OUT/obj/$n.o" | awk '$2 ~ /^[Tt]$/ {print $3}'); do
    [ -n "$(ops "$OUT/xs/$n.elf" "$fn")" ] || continue      # dropped by --gc-sections: never called
    if ! diff -q <(ops "$OUT/htif/$n.elf" "$fn") <(ops "$OUT/xs/$n.elf" "$fn") >/dev/null; then
      echo "WARN: $n $fn() differs between htif and xs builds" >&2
    fi
  done
  echo "built $n ($opt)"
}

failed=()
for n in $SUBSET; do build_one "$n" || { echo "FAILED $n" >&2; failed+=("$n"); }; done
nb=$(echo $SUBSET | wc -w)
if [ ${#failed[@]} -gt 0 ]; then
  echo "$((nb - ${#failed[@]}))/$nb built; FAILED: ${failed[*]}" >&2; exit 1
fi
echo "$nb/$nb built"
