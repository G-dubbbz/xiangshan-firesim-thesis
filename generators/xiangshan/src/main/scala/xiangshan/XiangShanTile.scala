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
// rocket-chip checkout. AXI4 field names/widths and cacheable_check/
// trace/clock/reset naming are now CONFIRMED against a full port dump of
// the actual IDUN-built XSTop.sv (2026-09-21) — see XSTopBlackBox.scala's
// header. `CoreParams` is still the main open risk: it's a trait whose
// exact field list drifts across rocket-chip versions/forks — before
// this compiles, diff the fields below against:
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
import freechips.rocketchip.prci.ClockCrossingType
import freechips.rocketchip.prci.ClockSinkParameters

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
  xLen: Int = 64,                         // XiangShan is RV64-only
  pgLevels: Int = 3,                      // sv39 — confirmed via build/XSTop.dts riscv,isa-extensions
                                           // (no sv48/sv57 flag seen); inert for a blackbox core either way
  bootFreqHz: BigInt = BigInt(1000000000),
  useVM: Boolean = true,
  useUser: Boolean = true,
  useSupervisor: Boolean = true,
  useHypervisor: Boolean = true,          // MinimalConfig ISA includes "h" — confirmed via build/XSTop.dts riscv,isa-extensions
  useDebug: Boolean = true,
  useAtomics: Boolean = true,
  useAtomicsOnlyForIO: Boolean = false,
  useCompressed: Boolean = true,          // "c" in riscv,isa-extensions
  override val useVector: Boolean = true, // "v"/"zv*" in riscv,isa-extensions — CoreParams gives this
                                           // field a default (= false), so it must be `override`d here
                                           // (confirmed against rocket-chip/src/main/scala/tile/Core.scala)
  useRVE: Boolean = false,
  useConditionalZero: Boolean = true,     // zicond
  mulDiv: Option[MulDivParams] = Some(MulDivParams()),
  fpu: Option[FPUParams] = Some(FPUParams()),
  fetchWidth: Int = 8,                    // [INERT PLACEHOLDER] no Chipyard-side icache/decode logic
                                           // reads this for a blackbox core (icache = None below); real
                                           // XiangShan fetch width is internal to XSTop, not modeled here
  decodeWidth: Int = 1,                   // inert for a blackbox core; some CoreParams variants omit this field
  retireWidth: Int = 8,                   // [INERT PLACEHOLDER] same reasoning as fetchWidth
  instBits: Int = 32,
  nLocalInterrupts: Int = 0,
  useNMI: Boolean = false,                // matches the nmi_0_0/nmi_0_1 tie-offs to false in XiangShanTileModuleImp
  nPMPs: Int = 0,                         // XSTop has its own internal PMA checker (see baseline doc
                                           // "io_cacheable_check — fully resolved"); Chipyard's PMP is unused.
  pmpGranularity: Int = 4,
  nBreakpoints: Int = 0,
  useBPWatch: Boolean = false,
  mcontextWidth: Int = 0,
  scontextWidth: Int = 0,
  nPerfCounters: Int = 0,
  haveBasicCounters: Boolean = true,
  haveFSDirty: Boolean = false,           // [INERT PLACEHOLDER] Rocket-specific FPU dirty-bit tracking
                                           // detail; XSTop's FPU state is internal and opaque to Chipyard
  haveCFlush: Boolean = false,
  misaWritable: Boolean = false,
  nL2TLBEntries: Int = 0,
  nL2TLBWays: Int = 0,
  nPTECacheEntries: Int = 0,
  mtvecInit: Option[BigInt] = Some(BigInt(0)),
  mtvecWritable: Boolean = true,
  lrscCycles: Int = 80,
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
  blockerCtrlAddr: Option[BigInt] = None,        // required by TileParams; XiangShan has no TileLink
                                                  // blocker device (matches RocketTileParams's default)
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(), // required by HierarchicalElementParams
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
  /* val dmaAXI4Node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = Seq(AddressSet(0x0, (BigInt(1) << 48) - 1)),
      supportsWrite = TransferSizes(1, 256),
      supportsRead  = TransferSizes(1, 256))),
    beatBytes = 32))) */

  /* (dmaAXI4Node
    := AXI4Buffer()
    := AXI4UserYanker()
    := AXI4Deinterleaver(64)
    := TLToAXI4()
    := TLWidthWidget(32)
    := TLBuffer()
    := slaveNode) */

  // Required by SinksExternalInterrupts (`def cpuDevice: Device`) --
  // minimal device-tree "cpu" node, modeled on RocketTile's cpuDevice but
  // with the Rocket-specific dtim/itim/beu properties dropped (XiangShan
  // has no Chipyard-side DTIM/ITIM/bus-error-unit devices -- those, like
  // the L1 caches, are internal to XSTop).
  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("ucb-bar,xiangshan", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ cpuProperties ++ nextLevelCacheProperty ++ tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(tileId))
  }

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

  // ---- Clock / reset [CONFIRMED — XSTop's actual ports are io_clock/
  // io_reset, NOT bare clock/reset; a Chisel BlackBox has no implicit
  // clock/reset the way a Module does, so this must be explicit] ----
  core.io_clock := clock
  core.io_reset := reset.asAsyncReset
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
  // `outer.decodeCoreInterrupts(...)` (SinksExternalInterrupts) has real
  // signature `decodeCoreInterrupts(core: TileInterrupts): Unit` (confirmed
  // against rocket-chip/src/main/scala/tile/Interrupts.scala) -- it wires
  // a Wire(new TileInterrupts()) from the tile's own intSinkNode; it does
  // not return anything to assign. It is also the WRONG shape for
  // io_extIntrs: XSTop documents io_extIntrs[63:0] as "bit i -> internal
  // PLIC source i+1" -- XiangShan has its OWN internal PLIC and wants raw,
  // pre-arbitration external device interrupt sources, not the handful of
  // already-arbitrated per-hart CLINT/PLIC lines (debug/mtip/msip/meip/
  // seip) that TileInterrupts/decodeCoreInterrupts produce. Wiring those
  // into io_extIntrs would compile but be semantically wrong.
  // OPEN ISSUE: real fix needs a new diplomatic interrupt node on this
  // tile tapping the subsystem's raw external/device interrupt sources
  // (bypassing HasPeripheryPLIC's own consumption of them) -- not
  // SinksExternalInterrupts. Deferred: tied to 0 for now, correct for a
  // minimal bare-metal smoke test with no external interrupt sources.
  core.io_extIntrs := 0.U

  // core.io_riscv_halt_0 / core.io_riscv_critical_error_0 are outputs;
  // left unconnected for now, expose via SourcesExternalNotifications if desired.

  // ---- AXI4 field-by-field connections [CONFIRMED field names —
  // flat AXI-standard short names, no `bits_` hierarchy, no `io_`
  // prefix — see XSTopBlackBox.scala / baseline doc for the full
  // grep-verified port dump this is transcribed from] ----

  // memory_*
  outer.memAXI4Node.out.foreach { case (axi4, _) =>
    core.memory.awvalid   := axi4.aw.valid
    axi4.aw.ready         := core.memory.awready
    core.memory.awid      := axi4.aw.bits.id
    core.memory.awaddr    := axi4.aw.bits.addr
    core.memory.awlen     := axi4.aw.bits.len
    core.memory.awsize    := axi4.aw.bits.size
    core.memory.awburst   := axi4.aw.bits.burst
    core.memory.awlock    := axi4.aw.bits.lock
    core.memory.awcache   := axi4.aw.bits.cache
    core.memory.awprot    := axi4.aw.bits.prot
    core.memory.awqos     := axi4.aw.bits.qos

    core.memory.wvalid    := axi4.w.valid
    axi4.w.ready          := core.memory.wready
    core.memory.wdata     := axi4.w.bits.data
    core.memory.wstrb     := axi4.w.bits.strb
    core.memory.wlast     := axi4.w.bits.last

    axi4.b.valid          := core.memory.bvalid
    core.memory.bready    := axi4.b.ready
    axi4.b.bits.id        := core.memory.bid
    axi4.b.bits.resp      := core.memory.bresp

    core.memory.arvalid   := axi4.ar.valid
    axi4.ar.ready         := core.memory.arready
    core.memory.arid      := axi4.ar.bits.id
    core.memory.araddr    := axi4.ar.bits.addr
    core.memory.arlen     := axi4.ar.bits.len
    core.memory.arsize    := axi4.ar.bits.size
    core.memory.arburst   := axi4.ar.bits.burst
    core.memory.arlock    := axi4.ar.bits.lock
    core.memory.arcache   := axi4.ar.bits.cache
    core.memory.arprot    := axi4.ar.bits.prot
    core.memory.arqos     := axi4.ar.bits.qos

    axi4.r.valid          := core.memory.rvalid
    core.memory.rready    := axi4.r.ready
    axi4.r.bits.id        := core.memory.rid
    axi4.r.bits.data      := core.memory.rdata
    axi4.r.bits.resp      := core.memory.rresp
    axi4.r.bits.last      := core.memory.rlast
  }

  // peripheral_* — identical shape to memory_*, narrower widths.
  outer.periphAXI4Node.out.foreach { case (axi4, _) =>
    core.peripheral.awvalid := axi4.aw.valid
    axi4.aw.ready            := core.peripheral.awready
    core.peripheral.awid     := axi4.aw.bits.id
    core.peripheral.awaddr   := axi4.aw.bits.addr
    core.peripheral.awlen    := axi4.aw.bits.len
    core.peripheral.awsize   := axi4.aw.bits.size
    core.peripheral.awburst  := axi4.aw.bits.burst
    core.peripheral.awlock   := axi4.aw.bits.lock
    core.peripheral.awcache  := axi4.aw.bits.cache
    core.peripheral.awprot   := axi4.aw.bits.prot
    core.peripheral.awqos    := axi4.aw.bits.qos

    core.peripheral.wvalid   := axi4.w.valid
    axi4.w.ready             := core.peripheral.wready
    core.peripheral.wdata    := axi4.w.bits.data
    core.peripheral.wstrb    := axi4.w.bits.strb
    core.peripheral.wlast    := axi4.w.bits.last

    axi4.b.valid             := core.peripheral.bvalid
    core.peripheral.bready   := axi4.b.ready
    axi4.b.bits.id           := core.peripheral.bid
    axi4.b.bits.resp         := core.peripheral.bresp

    core.peripheral.arvalid  := axi4.ar.valid
    axi4.ar.ready            := core.peripheral.arready
    core.peripheral.arid     := axi4.ar.bits.id
    core.peripheral.araddr   := axi4.ar.bits.addr
    core.peripheral.arlen    := axi4.ar.bits.len
    core.peripheral.arsize   := axi4.ar.bits.size
    core.peripheral.arburst  := axi4.ar.bits.burst
    core.peripheral.arlock   := axi4.ar.bits.lock
    core.peripheral.arcache  := axi4.ar.bits.cache
    core.peripheral.arprot   := axi4.ar.bits.prot
    core.peripheral.arqos    := axi4.ar.bits.qos

    axi4.r.valid             := core.peripheral.rvalid
    core.peripheral.rready   := axi4.r.ready
    axi4.r.bits.id           := core.peripheral.rid
    axi4.r.bits.data         := core.peripheral.rdata
    axi4.r.bits.resp         := core.peripheral.rresp
    axi4.r.bits.last         := core.peripheral.rlast
  }

  // dma_* unused for first bring-up (open issue #15): no inbound master, hold idle.
  core.dma.awvalid := false.B; core.dma.awid := 0.U;   core.dma.awaddr  := 0.U
  core.dma.awlen   := 0.U;     core.dma.awsize := 0.U; core.dma.awburst := 0.U
  core.dma.awlock  := 0.U;     core.dma.awcache := 0.U; core.dma.awprot := 0.U
  core.dma.awqos   := 0.U
  core.dma.wvalid  := false.B; core.dma.wdata := 0.U;  core.dma.wstrb := 0.U
  core.dma.wlast   := false.B
  core.dma.bready  := false.B
  core.dma.arvalid := false.B; core.dma.arid := 0.U;   core.dma.araddr  := 0.U
  core.dma.arlen   := 0.U;     core.dma.arsize := 0.U; core.dma.arburst := 0.U
  core.dma.arlock  := 0.U;     core.dma.arcache := 0.U; core.dma.arprot := 0.U
  core.dma.arqos   := 0.U
  core.dma.rready  := false.B

  // ---- cacheable_check [CONFIRMED — tie-off, per baseline doc] ----
  // No internal consumer anywhere; tie every req input to 0. resp outputs
  // are BlackBox outputs and don't need a sink.
  core.io_cacheable_check_req_0_valid     := false.B
  core.io_cacheable_check_req_0_bits_addr := 0.U
  core.io_cacheable_check_req_0_bits_size := 0.U
  core.io_cacheable_check_req_0_bits_cmd  := 0.U
  core.io_cacheable_check_req_1_valid     := false.B
  core.io_cacheable_check_req_1_bits_addr := 0.U
  core.io_cacheable_check_req_1_bits_size := 0.U
  core.io_cacheable_check_req_1_bits_cmd  := 0.U

  // ---- trace interface [CONFIRMED — tie-off for first bring-up] ----
  core.io_traceCoreInterface_0_fromEncoder_enable := false.B
  core.io_traceCoreInterface_0_fromEncoder_stall  := false.B
  // toEncoder_* outputs left unconnected — not needed until trace is wired up.

  // ---- Misc tie-offs for first bring-up [CONFIRMED] ----
  core.io_sram_config := 0.U
  core.io_pll0_lock   := true.B
  core.nmi_0_0        := false.B
  core.nmi_0_1        := false.B
  // core.io_pll0_ctrl_0..5 are outputs — left unconnected deliberately.

  // ---- JTAG: tied off for first bring-up (open issue #12) ----
  // DTM held in reset; program loading goes via serial-TL/LOADMEM, not JTAG.
  core.io_systemjtag_jtag_TCK      := false.B.asClock
  core.io_systemjtag_jtag_TMS      := false.B
  core.io_systemjtag_jtag_TDI      := false.B
  core.io_systemjtag_reset         := true.B.asAsyncReset
  core.io_systemjtag_mfr_id        := 0.U
  core.io_systemjtag_part_number   := 0.U
  core.io_systemjtag_version       := 0.U
  // TDO_data, TDO_driven, io_debug_reset are outputs: left unconnected.
}
