#!/usr/bin/env bash
# run-xs.sh <elf> [timeout_cycles] -- run a bare-metal test on chipyard.XiangShanConfig
# (direct Verilator). Preloads DRAM with LOADMEM=1 and zeroes tohost/fromhost in the
# uncached scratchpad (random at start in simulation) before the host starts polling.
# Run inside the container. Output: $CHIPYARD_DIR/sims/verilator/output/<config>/<elf>.{log,out}
set -euo pipefail
ELF=$(readlink -f "${1:?usage: run-xs.sh <elf> [timeout_cycles]}")
TIMEOUT=${2:-3000000}
CHIPYARD_DIR=${CHIPYARD_DIR:-/work/chipyard}
TOHOST=0x08000000      # must match tests/link.ld
FROMHOST=0x08000040
INIT="+init_write=${TOHOST}:0x0 +init_write=$(printf '0x%x' $((TOHOST+4))):0x0"
INIT="$INIT +init_write=${FROMHOST}:0x0 +init_write=$(printf '0x%x' $((FROMHOST+4))):0x0"
export LC_ALL=C.UTF-8
cd "$CHIPYARD_DIR/sims/verilator"
time make CONFIG=XiangShanConfig run-binary LOADMEM=1 TIMEOUT_CYCLES="$TIMEOUT" \
  BINARY="$ELF" EXTRA_SIM_FLAGS="$INIT"
