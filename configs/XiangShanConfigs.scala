// XiangShanConfigs.scala
//
// Installed into Chipyard as:
//   generators/chipyard/src/main/scala/config/XiangShanConfigs.scala
// by scripts/xs_install_into_chipyard.sh.
//
// XiangShanConfig: one XiangShan tile (XSTop Verilog blackbox, MinimalConfig RTL)
// in an otherwise default Chipyard SoC, with a 256-bit system bus so XSTop's
// 32-byte memory_* beats are not narrowed on the way to the L2.
//
// Known caveats (see docs/REPRODUCE.md and the bring-up log):
//   - Chipyard's L2 is kept, so XiangShan's own internal 4 MB HuanCun L3 sits in
//     front of it (double LLC). Fine for functional bring-up, NOT for benchmarks.
//   - The tile boots at 0x80000000 (resetVectorOverride); run programs with LOADMEM=1.
//   - tohost/fromhost live in an uncached scratchpad at 0x0800_0000 (see below and
//     tests/link.ld), because XiangShan's caches are not coherent with the host and
//     this XiangShan build has no cbo.flush (CMO needs EnableCHI). Runs must zero them
//     with +init_write (tests/run-xs.sh does this).

package chipyard

import org.chipsalliance.cde.config.Config

class XiangShanConfig extends Config(
  // 64 KiB SRAM on the system bus at 0x0800_0000. XiangShan's PMA treats
  // [0x0, 0x1000_0000) as uncached r/w, so stores here leave XSTop immediately
  // through the peripheral port and the host (fesvr over serial-TL) sees them.
  new testchipip.soc.WithSbusScratchpad(base = 0x08000000L, size = (64 << 10)) ++
  new xiangshan.WithNXiangShanCores(1) ++
  new xiangshan.WithXiangShanSystemBusWidth ++   // 256-bit sbus to match memory_* 32B beats
  new chipyard.config.AbstractConfig)
