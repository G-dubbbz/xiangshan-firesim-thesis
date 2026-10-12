# generators/xiangshan

Chipyard generator that wraps XiangShan's generated `XSTop` Verilog as a
Chipyard tile. Status: elaborates, builds in direct Chipyard Verilator, and runs
bare-metal code (UART "Hello XiangShan" smoke test, 2026-10-11).

Full rebuild instructions: [`docs/REPRODUCE.md`](../../docs/REPRODUCE.md).

| File | Role |
|---|---|
| `src/main/scala/xiangshan/XSTopBlackBox.scala` | `XSTop` as a Chisel `ExtModule`; every port matches `build/rtl/XSTop.sv`. The `addResource` list at the bottom is **generated** by `scripts/xs_stage_rtl.sh`. |
| `src/main/scala/xiangshan/XiangShanTile.scala` | Tile params, diplomatic nodes (AXI4→TileLink bridges for `memory_*` and `peripheral_*`), XSTop wiring and tie-offs, reset-vector override, and a temporary `[XSPROBE]` debug probe. |
| `src/main/scala/xiangshan/Configs.scala` | `WithNXiangShanCores(n)`, `WithXiangShanSystemBusWidth` (256-bit sbus). |
| `src/main/resources/vsrc/` | **Not committed.** Filled by `scripts/xs_stage_rtl.sh` from a clean XiangShan `make verilog` (~1581 files, all modules except `XSTop` prefixed `XS_`). |

The top-level Chipyard config (`XiangShanConfig`) lives in
`configs/XiangShanConfigs.scala`, and the `build.sbt` change in
`patches/chipyard/xiangshan-build-sbt.patch`. `scripts/xs_install_into_chipyard.sh`
installs all three into a Chipyard checkout.

Key integration decisions (details in docs/REPRODUCE.md):
- `dma_*` is tied idle (no inbound DMA yet).
- `memory_*` 14-bit AXI IDs are compressed with `AXI4IdIndexer(4)` +
  `AXI4UserYanker(Some(8))`. These are performance tunables.
- The tile boots at `0x80000000` (`resetVectorOverride`), because XiangShan's PMA
  does not allow fetching from Chipyard's bootrom at `0x10000`.
- `useVector = false` in the Chipyard-side params (DTS lacks `v`); JTAG, RTC
  clock and external interrupts are tied off.
