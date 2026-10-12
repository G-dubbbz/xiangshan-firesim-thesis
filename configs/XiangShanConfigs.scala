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

package chipyard

import org.chipsalliance.cde.config.Config

class XiangShanConfig extends Config(
  new xiangshan.WithNXiangShanCores(1) ++
  new xiangshan.WithXiangShanSystemBusWidth ++   // 256-bit sbus to match memory_* 32B beats
  new chipyard.config.AbstractConfig)
