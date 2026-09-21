// XiangShanTile.scala
//
// Chipyard tile wrapper around the XSTop blackbox (see XSTopBlackBox.scala).
// Structure follows the Verilog-blackbox-tile pattern documented in
// `xiangshan-baseline.md` ("Reference pattern for a Verilog-blackbox tile",
// read directly from ucb-bar/cva6-wrapper's CVA6Tile.scala + Chipyard's
// custom-core doc), adapted for XiangShan's three-AXI-port shape (two
// masters instead of CVA6's one, plus one slave port with no CVA6
// analogue at all).
//
// STATUS: v0 draft, NOT YET COMPILED against the user's actual Chipyard/
// rocket-chip checkout. `CoreParams` in particular is a trait whose exact
// field list drifts across rocket-chip versions/forks — before this
// compiles, diff the fields below against:
//   grep -n "trait CoreParams" -A 60 \
//     generators/rocket-chip/src/main/scala/tile/Core.scala
// and adjust. Treat the first `sbt compile` output on this file as the
// next debugging step, not a sign something was done wrong — this is
// exactly the kind of incremental-validation problem the project favors
// over guessing further from here.

package xiangshan

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

// ---------------------------------------------------------------------
// Core / tile parameter case classes
// ---------------------------------------------------------------------

/** [DRAFT — verify every field against the local CoreParams trait]
  * Mostly-inert constants: XSTop is an opaque blackbox, so almost none of
  * these actually influence generated hardware (no rocket/BOOM decode
  * logic reads them). They exist to satisfy `CoreParams` and to feed the
  * handful of BaseTile mechanisms that DO matter for a blackbox core:
  * `mtvecInit`/`resetVectorLen` (-> resetVectorSinkNode -> io_riscv_rst_vec_0),
  * and `nPMPs`/`pmpGranularity` if Chipyard's own PMP-adjacent plumbing
  * is exercised anywhere in the subsystem (it generally isn't, for a
  * blackbox core, since XSTop has its own internal PMA/PMP checker per
  * the baseline doc's `io_cacheable_check` findings — XiangShan does its
  * own permission checking, Chipyard's rocket-chip PMP machinery is not
  * driving anything real here).
  */
case class XiangShanCoreParams(
  bootFreqHz: BigInt = BigInt(1000000000),
  useVM: Boolean = true,
  useUser: Boolean = true,
  useSupervisor: Boolean = true,
  useHypervisor: Boolean = true,          // MinimalConfig ISA includes "h" — confirmed via build/XSTop.dts riscv,isa-extensions
  useDebug: Boolean = true,
  useAtomics: Boolean = true,
  useAtomicsOnlyForIO: Boolean = false,
  useCompressed: Boolean = true,          // "c" in riscv,isa-extensions
  useVector: Boolean = true,              // "v"/"zv*" in riscv,isa-extensions — NOTE: base CoreParams
                                           // may not expose this field on older rocket-chip; if compile
                                           // fails here, this belongs in a vector-specific subtrait instead.
  useRVE: Boolean = false,
  useConditionalZero: Boolean = true,     // zicond
  mulDiv: Option[MulDivParams] = Some(MulDivParams()),
  fpu: Option[FPUParams] = Some(FPUParams()),
  nLocalInterrupts: Int = 0,
  nPMPs: Int = 0,                         // XSTop has its own internal PMA checker (see baseline doc
                                           // "io_cacheable_check — fully resolved"); Chipyard's PMP is unused.
  pmpGranularity: Int = 4,
  nBreakpoints: Int = 0,
  useBPWatch: Boolean = false,
  mcontextWidth: Int = 0,
  scontextWidth: Int = 0,
  nPerfCounters: Int = 0,
  haveBasicCounters: Boolean = true,
  haveCFlush: Boolean = false,
  misaWritable: Boolean = false,
  nL2TLBEntries: Int = 0,
  nL2TLBWays: Int = 0,
  nPTECacheEntries: Int = 0,
  mtvecInit: Option[BigInt] = Some(BigInt(0)),
  mtvecWritable: Boolean = true,
  instBits: Int = 32,
  lrscCycles: Int = 80,
  decodeWidth: Int = 1,                   // inert for a blackbox core; some CoreParams variants omit this field
  traceHasWdata: Boolean = false,
  useZba: Boolean = true,
  useZbb: Boolean = true,
  useZbs: Boolean = true
) extends CoreParams

/** [DRAFT] TileParams instantiation, mirroring CVA6TileParams's shape. */
case class XiangShanTileParams(
  name: Option[String] = Some("xiangshan_tile"),
  tileId: Int = 0,
  core: XiangShanCoreParams = XiangShanCoreParams(),
  icache: Option[ICacheParams] = None,   // XSTop has its own internal L1I; nothing for Chipyard's
                                          // ICacheParams machinery to generate.
  dcache: Option[DCacheParams] = None,   // same for L1D — XSTop's DCache is entirely internal.
  btb: Option[BTBParams] = None,
  trace: Boolean = false,                // flip true once io_traceCoreInterface_0_* is wired (TODO, see
                                          // XSTopBlackBox.scala)
  baseName: String = "xiangshan_tile",
  uniqueName: String = "xiangshan_tile_0"
) extends InstantiableTileParams[XiangShanTile] {
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)
                  (implicit p: Parameters): XiangShanTile = {
    new XiangShanTile(this, crossing, lookup)
  }
}

/** [DRAFT] CanAttachTile glue, same shape as every other tile attach params
  * case class in Chipyard (`RocketTileAttachParams`, `CVA6TileAttachParams`, ...).
  */
case class XiangShanTileAttachParams(
  tileParams: XiangShanTileParams,
  crossingParams: HierarchicalElementCrossingParamsLike
) extends CanAttachTile {
  type TileType = XiangShanTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

// ---------------------------------------------------------------------
// Tile
// ---------------------------------------------------------------------

/** [DRAFT] Declares diplomatic nodes only — no XiangShan RTL lives here,
  * only the AXI4<->TileLink bridging and the tile-level plumbing BaseTile
  * expects. The actual XSTop instantiation happens in
  * XiangShanTileModuleImp below.
  */
class XiangShanTile private (
  val xiangshanParams: XiangShanTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters
) extends BaseTile(xiangshanParams, crossing, lookup, q)
  with SinksExternalInterrupts
  with SourcesExternalNotifications {

  def this(params: XiangShanTileParams, crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)
           (implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  // No RoCC / custom tile-internal slave target beyond what dma_* needs
  // (matches CVA6Tile.scala exactly).
  val intOutwardNode = None
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  // ---- memory_* : cacheable main-memory master (48-bit addr, 256-bit data, 14-bit id) ----
  val memAXI4Node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters(name = s"${xiangshanParams.uniqueName}_memory", id = IdRange(0, 1 << 14))))))

  (tlMasterXbar.node
    := TLBuffer()
    := TLWidthWidget(32)  // 256 bits = 32 bytes; matches AXI4 data width exactly, no narrowing needed
    := TLFIFOFixer()
    := AXI4ToTL()
    := AXI4UserYanker(Some(1))
    := AXI4Fragmenter()
    := memAXI4Node)

  // ---- peripheral_* : uncached MMIO master (31-bit addr, 64-bit data, 2-bit id) ----
  val periphAXI4Node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters(name = s"${xiangshanParams.uniqueName}_peripheral", id = IdRange(0, 1 << 2))))))

  (tlMasterXbar.node
    := TLBuffer()
    := TLWidthWidget(8)   // 64 bits = 8 bytes
    := TLFIFOFixer()
    := AXI4ToTL()
    := AXI4UserYanker(Some(1))
    := AXI4Fragmenter()
    := periphAXI4Node)

  // ---- dma_* : XSTop is the AXI4 SLAVE here (inbound DMA into XiangShan's
  // coherence domain) — the reverse chain of SoC.scala's own
  // HaveSlaveAXI4Port trait (baseline doc, "dma_* — confirmed"),
  // hung off tlSlaveXbar / this tile's slaveNode rather than reusing
  // XiangShan's internal chain (which we don't have access to — XSTop is
  // opaque past its port boundary).
  val dmaAXI4Node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(0x0, (BigInt(1) << 48) - 1)),
      supportsWrite = TransferSizes(1, 256),
      supportsRead  = TransferSizes(1, 256))),
    beatBytes = 32)))

  (dmaAXI4Node
    := AXI4Buffer()
    := AXI4UserYanker()
    := AXI4Deinterleaver(64)
    := TLToAXI4()
    := TLWidthWidget(32)
    := TLBuffer()
    := slaveNode)

  override lazy val module = new XiangShanTileModuleImp(this)

  // Required by SinksExternalInterrupts — a single hart's worth of the
  // io_extIntrs[63:0] vector, wired by decodeCoreInterrupts() in the
  // ModuleImp. nLocalInterrupts is otherwise 0 (see CoreParams above);
  // int line count here corresponds to what Chipyard-side devices will
  // route into io_extIntrs, decided at the WithNXiangShanCores /
  // subsystem-interrupt-map level, not fixed in this file.
}

// ---------------------------------------------------------------------
// Tile module implementation
// ---------------------------------------------------------------------

class XiangShanTileModuleImp(outer: XiangShanTile) extends BaseTileModuleImp(outer) {

  val core = Module(new XSTop)

  // ---- Clock / reset [CONFIRMED wiring per baseline doc] ----
  core.clock := clock
  core.reset := reset.asAsyncReset
  // core.io_rtc_clock must be driven from a real RTC clock source, not
  // tied off — wire to whatever Chipyard's own RTC/timer clock domain is
  // (e.g. `outer.rtcClockNode` if one is added, or the system's existing
  // low-frequency reference clock). NOT YET WIRED — placeholder:
  core.io_rtc_clock := clock  // TODO: replace with a real RTC domain before Verilator bring-up

  // ---- Reset vector [CONFIRMED via BaseTile.resetVectorSinkNode] ----
  core.io_riscv_rst_vec_0 := outer.resetVectorSinkNode.bundle

  // ---- Interrupts [CONFIRMED bit mapping — baseline doc open issue #2] ----
  // outer.decodeCoreInterrupts(...) is rocket-chip's standard helper that
  // turns the tile's interruptSourceNode bundle into a flat Vec/UInt in
  // priority order; here it must line up 1:1 with io_extIntrs bit i ->
  // XiangShan-internal PLIC source i+1. Verify the ordering
  // decodeCoreInterrupts produces matches the source ordering Chipyard's
  // own subsystem interrupt map assigns, since XiangShan (not Chipyard)
  // owns the PLIC here — Chipyard is only a source, never a sink, of
  // these 64 lines.
  val interrupts = Wire(UInt(64.W))
  interrupts := outer.decodeCoreInterrupts(interrupts.asBools)(0).asUInt // TODO: confirm exact API shape
                                                                          // against the local rocket-chip
                                                                          // version; this line is the
                                                                          // most likely one to need
                                                                          // adjustment.
  core.io_extIntrs := interrupts

  core.io_riscv_halt_0           // unconnected output, expose via SourcesExternalNotifications if desired
  core.io_riscv_critical_error_0 // ditto

  // ---- AXI4 field-by-field connections ----
  // memory_*
  outer.memAXI4Node.out.foreach { case (axi4, _) =>
    core.io_memory.aw_valid          := axi4.aw.valid
    axi4.aw.ready                    := core.io_memory.aw_ready
    core.io_memory.aw_bits_id        := axi4.aw.bits.id
    core.io_memory.aw_bits_addr      := axi4.aw.bits.addr
    core.io_memory.aw_bits_len       := axi4.aw.bits.len
    core.io_memory.aw_bits_size      := axi4.aw.bits.size
    core.io_memory.aw_bits_burst     := axi4.aw.bits.burst
    core.io_memory.aw_bits_lock      := axi4.aw.bits.lock
    core.io_memory.aw_bits_cache     := axi4.aw.bits.cache
    core.io_memory.aw_bits_prot      := axi4.aw.bits.prot
    core.io_memory.aw_bits_qos       := axi4.aw.bits.qos

    core.io_memory.w_valid           := axi4.w.valid
    axi4.w.ready                     := core.io_memory.w_ready
    core.io_memory.w_bits_data       := axi4.w.bits.data
    core.io_memory.w_bits_strb       := axi4.w.bits.strb
    core.io_memory.w_bits_last       := axi4.w.bits.last

    axi4.b.valid                     := core.io_memory.b_valid
    core.io_memory.b_ready           := axi4.b.ready
    axi4.b.bits.id                   := core.io_memory.b_bits_id
    axi4.b.bits.resp                 := core.io_memory.b_bits_resp

    core.io_memory.ar_valid          := axi4.ar.valid
    axi4.ar.ready                    := core.io_memory.ar_ready
    core.io_memory.ar_bits_id        := axi4.ar.bits.id
    core.io_memory.ar_bits_addr      := axi4.ar.bits.addr
    core.io_memory.ar_bits_len       := axi4.ar.bits.len
    core.io_memory.ar_bits_size      := axi4.ar.bits.size
    core.io_memory.ar_bits_burst     := axi4.ar.bits.burst
    core.io_memory.ar_bits_lock      := axi4.ar.bits.lock
    core.io_memory.ar_bits_cache     := axi4.ar.bits.cache
    core.io_memory.ar_bits_prot      := axi4.ar.bits.prot
    core.io_memory.ar_bits_qos       := axi4.ar.bits.qos

    axi4.r.valid                     := core.io_memory.r_valid
    core.io_memory.r_ready           := axi4.r.ready
    axi4.r.bits.id                   := core.io_memory.r_bits_id
    axi4.r.bits.data                 := core.io_memory.r_bits_data
    axi4.r.bits.resp                 := core.io_memory.r_bits_resp
    axi4.r.bits.last                 := core.io_memory.r_bits_last
  }

  // peripheral_* — identical shape to memory_*, narrower widths.
  outer.periphAXI4Node.out.foreach { case (axi4, _) =>
    core.io_peripheral.aw_valid      := axi4.aw.valid
    axi4.aw.ready                    := core.io_peripheral.aw_ready
    core.io_peripheral.aw_bits_id    := axi4.aw.bits.id
    core.io_peripheral.aw_bits_addr  := axi4.aw.bits.addr
    core.io_peripheral.aw_bits_len   := axi4.aw.bits.len
    core.io_peripheral.aw_bits_size  := axi4.aw.bits.size
    core.io_peripheral.aw_bits_burst := axi4.aw.bits.burst
    core.io_peripheral.aw_bits_lock  := axi4.aw.bits.lock
    core.io_peripheral.aw_bits_cache := axi4.aw.bits.cache
    core.io_peripheral.aw_bits_prot  := axi4.aw.bits.prot
    core.io_peripheral.aw_bits_qos   := axi4.aw.bits.qos

    core.io_peripheral.w_valid       := axi4.w.valid
    axi4.w.ready                     := core.io_peripheral.w_ready
    core.io_peripheral.w_bits_data   := axi4.w.bits.data
    core.io_peripheral.w_bits_strb   := axi4.w.bits.strb
    core.io_peripheral.w_bits_last   := axi4.w.bits.last

    axi4.b.valid                     := core.io_peripheral.b_valid
    core.io_peripheral.b_ready       := axi4.b.ready
    axi4.b.bits.id                   := core.io_peripheral.b_bits_id
    axi4.b.bits.resp                 := core.io_peripheral.b_bits_resp

    core.io_peripheral.ar_valid      := axi4.ar.valid
    axi4.ar.ready                    := core.io_peripheral.ar_ready
    core.io_peripheral.ar_bits_id    := axi4.ar.bits.id
    core.io_peripheral.ar_bits_addr  := axi4.ar.bits.addr
    core.io_peripheral.ar_bits_len   := axi4.ar.bits.len
    core.io_peripheral.ar_bits_size  := axi4.ar.bits.size
    core.io_peripheral.ar_bits_burst := axi4.ar.bits.burst
    core.io_peripheral.ar_bits_lock  := axi4.ar.bits.lock
    core.io_peripheral.ar_bits_cache := axi4.ar.bits.cache
    core.io_peripheral.ar_bits_prot  := axi4.ar.bits.prot
    core.io_peripheral.ar_bits_qos   := axi4.ar.bits.qos

    axi4.r.valid                     := core.io_peripheral.r_valid
    core.io_peripheral.r_ready       := axi4.r.ready
    axi4.r.bits.id                   := core.io_peripheral.r_bits_id
    axi4.r.bits.data                 := core.io_peripheral.r_bits_data
    axi4.r.bits.resp                 := core.io_peripheral.r_bits_resp
    axi4.r.bits.last                 := core.io_peripheral.r_bits_last
  }

  // dma_* — XSTop is the slave; directions flip relative to the two
  // master ports above (core drives b/r, external side drives aw/w/ar).
  outer.dmaAXI4Node.in.foreach { case (axi4, _) =>
    axi4.aw.valid                    := core.io_dma.aw_valid
    core.io_dma.aw_ready             := axi4.aw.ready
    axi4.aw.bits.id                  := core.io_dma.aw_bits_id
    axi4.aw.bits.addr                := core.io_dma.aw_bits_addr
    axi4.aw.bits.len                 := core.io_dma.aw_bits_len
    axi4.aw.bits.size                := core.io_dma.aw_bits_size
    axi4.aw.bits.burst               := core.io_dma.aw_bits_burst
    axi4.aw.bits.lock                := core.io_dma.aw_bits_lock
    axi4.aw.bits.cache               := core.io_dma.aw_bits_cache
    axi4.aw.bits.prot                := core.io_dma.aw_bits_prot
    axi4.aw.bits.qos                 := core.io_dma.aw_bits_qos

    axi4.w.valid                     := core.io_dma.w_valid
    core.io_dma.w_ready              := axi4.w.ready
    axi4.w.bits.data                 := core.io_dma.w_bits_data
    axi4.w.bits.strb                 := core.io_dma.w_bits_strb
    axi4.w.bits.last                 := core.io_dma.w_bits_last

    core.io_dma.b_valid              := axi4.b.valid
    axi4.b.ready                     := core.io_dma.b_ready
    core.io_dma.b_bits_id            := axi4.b.bits.id
    core.io_dma.b_bits_resp          := axi4.b.bits.resp

    axi4.ar.valid                    := core.io_dma.ar_valid
    core.io_dma.ar_ready             := axi4.ar.ready
    axi4.ar.bits.id                  := core.io_dma.ar_bits_id
    axi4.ar.bits.addr                := core.io_dma.ar_bits_addr
    axi4.ar.bits.len                 := core.io_dma.ar_bits_len
    axi4.ar.bits.size                := core.io_dma.ar_bits_size
    axi4.ar.bits.burst               := core.io_dma.ar_bits_burst
    axi4.ar.bits.lock                := core.io_dma.ar_bits_lock
    axi4.ar.bits.cache               := core.io_dma.ar_bits_cache
    axi4.ar.bits.prot                := core.io_dma.ar_bits_prot
    axi4.ar.bits.qos                 := core.io_dma.ar_bits_qos

    core.io_dma.r_valid              := axi4.r.valid
    axi4.r.ready                     := core.io_dma.r_ready
    core.io_dma.r_bits_id            := axi4.r.bits.id
    core.io_dma.r_bits_data          := axi4.r.bits.data
    core.io_dma.r_bits_resp          := axi4.r.bits.resp
    core.io_dma.r_bits_last          := axi4.r.bits.last
  }

  // ---- cacheable_check [CONFIRMED tie-off strategy, port names TODO] ----
  // Per baseline doc: no internal consumer anywhere; tie req.valid = 0,
  // leave resp unconnected. Cannot write the actual tie-off lines until
  // XSTopBlackBox.scala's io_cacheable_check_* fields are filled in from
  // a grep of XSTop.sv (see TODO there).

  // ---- Misc tie-offs for first bring-up [CONFIRMED] ----
  core.io_sram_config := 0.U
  core.io_pll0_lock   := true.B
  core.nmi_0_0        := false.B
  core.nmi_0_1        := false.B
  // core.io_pll0_ctrl_0..5 are outputs — left unconnected deliberately.

  // ---- JTAG ----
  // Wire from outer's systemjtag node the same way BaseTile's other
  // Debug-attached tiles do (see how RocketTile/CVA6Tile source
  // `outer.debugOpt`/systemjtag from the subsystem's DebugModuleKey) —
  // NOT YET WIRED. This tile currently leaves io_systemjtag_* and
  // io_debug_reset floating, which will not elaborate cleanly; needs a
  // real JTAG DTM source once ExportDebug/JTAG is confirmed enabled in
  // the target Chipyard config (BaseConfig sets
  // ExportDebug(protocols = Set(JTAG)) per the baseline doc, so a JTAG
  // source should already exist somewhere in the subsystem to tap).
}
