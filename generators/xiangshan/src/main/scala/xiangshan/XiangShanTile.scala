// XiangShanTile.scala
//
// Chipyard tile wrapper around the XSTop blackbox (see XSTopBlackBox.scala).
// Structure follows the Verilog-blackbox-tile pattern (ucb-bar/cva6-wrapper
// CVA6Tile.scala + Chipyard custom-core doc), adapted for XiangShan's
// three-AXI-port shape.
//
// STATUS (2026-10-09): compiles (`sbt "xiangshan/compile"`); Chipyard
// elaboration of XiangShanConfig in progress. Changes made during
// elaboration bring-up (see claude/xiangshan-elaboration-log.md):
//   1. dma_* manager node removed (it claimed the whole 48-bit address
//      space and overlapped DRAM); dma_* inputs tied idle. Open issue #15.
//   2. tlMasterXbar routed out through tlOtherMastersNode -> masterNode
//      (same as RocketTile.scala:119-120); without it the xbar had inputs
//      but no outputs (IndexOutOfBoundsException in TLXbar managerFn).
//   3. memory_* IDs compressed with AXI4IdIndexer(4) before the per-ID
//      adapters; 14-bit IDs made AXI4UserYanker/AXI4ToTL build ~16k
//      per-ID structures and the generator ran out of heap.
//      TUNABLES (affect memory-level parallelism, check before benchmarking):
//      AXI4IdIndexer bits = 4, AXI4UserYanker capacity = 8.
//   4. memory_*/peripheral_* field wiring direction corrected: XSTop is the
//      AXI4 MASTER on these ports (requests XSTop -> node).
//   5. JTAG inputs tied off (DTM held in reset). Open issue #12.
//
// Toolchain note: after ANY edit to this file, run
//   rm -f /work/chipyard/.classpath_cache/*.jar
// before `make`, because `fd` is missing in the container and make will
// otherwise reuse a stale chipyard.jar.

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

/** Mostly-inert constants: XSTop is an opaque blackbox, so almost none of
  * these influence generated hardware. They satisfy `CoreParams` (field
  * list checked against rocket-chip tile/Core.scala) and feed the few
  * BaseTile mechanisms that matter for a blackbox core (reset vector).
  */
case class XiangShanCoreParams(
  xLen: Int = 64,                         // XiangShan is RV64-only
  pgLevels: Int = 3,                      // sv39; inert for a blackbox core
  bootFreqHz: BigInt = BigInt(1000000000),
  useVM: Boolean = true,
  useUser: Boolean = true,
  useSupervisor: Boolean = true,
  useHypervisor: Boolean = true,          // "h" in build/XSTop.dts riscv,isa-extensions
  useDebug: Boolean = true,
  useAtomics: Boolean = true,
  useAtomicsOnlyForIO: Boolean = false,
  useCompressed: Boolean = true,
  override val useVector: Boolean = true, // CoreParams default is false, so override
  useRVE: Boolean = false,
  useConditionalZero: Boolean = true,     // zicond
  mulDiv: Option[MulDivParams] = Some(MulDivParams()),
  fpu: Option[FPUParams] = Some(FPUParams()),
  fetchWidth: Int = 8,                    // [INERT PLACEHOLDER]
  decodeWidth: Int = 1,                   // [INERT PLACEHOLDER]
  retireWidth: Int = 8,                   // [INERT PLACEHOLDER]
  instBits: Int = 32,
  nLocalInterrupts: Int = 0,
  useNMI: Boolean = false,                // matches nmi_0_0/nmi_0_1 tie-offs
  nPMPs: Int = 0,                         // XSTop has its own internal PMA/PMP
  pmpGranularity: Int = 4,
  nBreakpoints: Int = 0,
  useBPWatch: Boolean = false,
  mcontextWidth: Int = 0,
  scontextWidth: Int = 0,
  nPerfCounters: Int = 0,
  haveBasicCounters: Boolean = true,
  haveFSDirty: Boolean = false,           // [INERT PLACEHOLDER]
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

case class XiangShanTileParams(
  name: Option[String] = Some("xiangshan_tile"),
  tileId: Int = 0,
  core: XiangShanCoreParams = XiangShanCoreParams(),
  icache: Option[ICacheParams] = None,   // L1I is internal to XSTop
  dcache: Option[DCacheParams] = None,   // L1D is internal to XSTop
  btb: Option[BTBParams] = None,
  trace: Boolean = false,                // trace interface tied off for now
  blockerCtrlAddr: Option[BigInt] = None,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  baseName: String = "xiangshan_tile",
  uniqueName: String = "xiangshan_tile_0"
) extends InstantiableTileParams[XiangShanTile] {
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)
                  (implicit p: Parameters): XiangShanTile = {
    new XiangShanTile(this, crossing, lookup)
  }
}

case class XiangShanTileAttachParams(
  tileParams: XiangShanTileParams,
  crossingParams: HierarchicalElementCrossingParamsLike
) extends CanAttachTile {
  type TileType = XiangShanTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

// ---------------------------------------------------------------------
// Tile (diplomatic nodes only; XSTop is instantiated in the ModuleImp)
// ---------------------------------------------------------------------

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

  // No tile-internal slave devices (dma_* is disabled, see below), so
  // slaveNode stays unconnected and tlSlaveXbar is deliberately NOT wired
  // to it: an xbar with inputs but no outputs fails diplomacy.
  val intOutwardNode = None
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  // ---- memory_* : cacheable main-memory master (48-bit addr, 256-bit data, 14-bit id) ----
  val memAXI4Node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters(name = s"${xiangshanParams.uniqueName}_memory", id = IdRange(0, 1 << 14))))))

  (tlMasterXbar.node
    := TLBuffer()
    := TLWidthWidget(32)         // 256 bits = 32 bytes
    := TLFIFOFixer()
    := AXI4ToTL()
    := AXI4UserYanker(Some(8))   // TUNABLE: up to 8 in flight per compressed ID
    := AXI4Fragmenter()
    := AXI4IdIndexer(4)          // TUNABLE: 14-bit XSTop IDs -> 16 IDs (XiangShan's own DMA chain does the same)
    := memAXI4Node)

  // ---- peripheral_* : uncached MMIO master (31-bit addr, 64-bit data, 2-bit id) ----
  val periphAXI4Node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    Seq(AXI4MasterParameters(name = s"${xiangshanParams.uniqueName}_peripheral", id = IdRange(0, 1 << 2))))))

  (tlMasterXbar.node
    := TLBuffer()
    := TLWidthWidget(8)          // 64 bits = 8 bytes
    := TLFIFOFixer()
    := AXI4ToTL()
    := AXI4UserYanker(Some(1))
    := AXI4Fragmenter()
    := periphAXI4Node)

  // Route the tile's internal master xbar out of the tile
  // (same as RocketTile.scala:119-120; no master blocker since blockerCtrlAddr = None).
  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode

  // ---- dma_* : DISABLED for first bring-up (open issue #15) ----
  // The earlier AXI4SlaveNode declared AddressSet(0x0, 2^48-1) and
  // overlapped DRAM. Correct design: a TL CLIENT path converted TL->AXI4
  // that drives dma_* as AXI master and declares no address range.
  // Not needed for bare-metal tests; XSTop's dma_* inputs are tied idle
  // in the ModuleImp.

  // Device-tree "cpu" node required by SinksExternalInterrupts.
  // Modeled on RocketTile's cpuDevice without dtim/itim/beu properties.
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
}

// ---------------------------------------------------------------------
// Tile module implementation
// ---------------------------------------------------------------------

class XiangShanTileModuleImp(outer: XiangShanTile) extends BaseTileModuleImp(outer) {

  val core = Module(new XSTop)

  // ---- Clock / reset (XSTop ports are io_clock/io_reset) ----
  core.io_clock := clock
  core.io_reset := reset.asAsyncReset
  // TODO (open issue #12): io_rtc_clock should come from a real RTC domain.
  // Tied to the core clock for now: mtime ticks at core frequency.
  core.io_rtc_clock := clock

  // ---- Reset vector ----
  core.io_riscv_rst_vec_0 := outer.resetVectorSinkNode.bundle

  // ---- Interrupts (open issue #14) ----
  // io_extIntrs[i] -> XiangShan-internal PLIC source i+1. It needs raw
  // device interrupt sources, which TileInterrupts/decodeCoreInterrupts do
  // not provide. Tied to 0 for bare-metal bring-up.
  core.io_extIntrs := 0.U

  // io_riscv_halt_0 / io_riscv_critical_error_0: outputs, unconnected for now.

  // ---- memory_* / peripheral_* ----
  // XSTop is the AXI4 MASTER on these ports: request channels and response
  // `ready` flow XSTop -> node; response channels and request `ready` flow
  // node -> XSTop. Field names/widths from the IDUN XSTop.sv port dump.
  def connectXSMaster(axi4: AXI4Bundle, p: XSTopAXI4Port): Unit = {
    axi4.aw.valid      := p.awvalid
    p.awready          := axi4.aw.ready
    axi4.aw.bits.id    := p.awid
    axi4.aw.bits.addr  := p.awaddr
    axi4.aw.bits.len   := p.awlen
    axi4.aw.bits.size  := p.awsize
    axi4.aw.bits.burst := p.awburst
    axi4.aw.bits.lock  := p.awlock
    axi4.aw.bits.cache := p.awcache
    axi4.aw.bits.prot  := p.awprot
    axi4.aw.bits.qos   := p.awqos

    axi4.w.valid       := p.wvalid
    p.wready           := axi4.w.ready
    axi4.w.bits.data   := p.wdata
    axi4.w.bits.strb   := p.wstrb
    axi4.w.bits.last   := p.wlast

    p.bvalid           := axi4.b.valid
    axi4.b.ready       := p.bready
    p.bid              := axi4.b.bits.id
    p.bresp            := axi4.b.bits.resp

    axi4.ar.valid      := p.arvalid
    p.arready          := axi4.ar.ready
    axi4.ar.bits.id    := p.arid
    axi4.ar.bits.addr  := p.araddr
    axi4.ar.bits.len   := p.arlen
    axi4.ar.bits.size  := p.arsize
    axi4.ar.bits.burst := p.arburst
    axi4.ar.bits.lock  := p.arlock
    axi4.ar.bits.cache := p.arcache
    axi4.ar.bits.prot  := p.arprot
    axi4.ar.bits.qos   := p.arqos

    p.rvalid           := axi4.r.valid
    axi4.r.ready       := p.rready
    p.rid              := axi4.r.bits.id
    p.rdata            := axi4.r.bits.data
    p.rresp            := axi4.r.bits.resp
    p.rlast            := axi4.r.bits.last
  }

  outer.memAXI4Node.out.foreach    { case (axi4, _) => connectXSMaster(axi4, core.memory) }
  outer.periphAXI4Node.out.foreach { case (axi4, _) => connectXSMaster(axi4, core.peripheral) }

  // ---- dma_* : unused for first bring-up (open issue #15), hold idle ----
  // XSTop is the AXI4 slave here, so these are XSTop inputs.
  core.dma.awvalid := false.B; core.dma.awid := 0.U;    core.dma.awaddr  := 0.U
  core.dma.awlen   := 0.U;     core.dma.awsize := 0.U;  core.dma.awburst := 0.U
  core.dma.awlock  := 0.U;     core.dma.awcache := 0.U; core.dma.awprot  := 0.U
  core.dma.awqos   := 0.U
  core.dma.wvalid  := false.B; core.dma.wdata := 0.U;   core.dma.wstrb   := 0.U
  core.dma.wlast   := false.B
  core.dma.bready  := false.B
  core.dma.arvalid := false.B; core.dma.arid := 0.U;    core.dma.araddr  := 0.U
  core.dma.arlen   := 0.U;     core.dma.arsize := 0.U;  core.dma.arburst := 0.U
  core.dma.arlock  := 0.U;     core.dma.arcache := 0.U; core.dma.arprot  := 0.U
  core.dma.arqos   := 0.U
  core.dma.rready  := false.B

  // ---- cacheable_check: no consumer anywhere, tie requests off ----
  core.io_cacheable_check_req_0_valid     := false.B
  core.io_cacheable_check_req_0_bits_addr := 0.U
  core.io_cacheable_check_req_0_bits_size := 0.U
  core.io_cacheable_check_req_0_bits_cmd  := 0.U
  core.io_cacheable_check_req_1_valid     := false.B
  core.io_cacheable_check_req_1_bits_addr := 0.U
  core.io_cacheable_check_req_1_bits_size := 0.U
  core.io_cacheable_check_req_1_bits_cmd  := 0.U

  // ---- Trace interface: tied off for first bring-up ----
  core.io_traceCoreInterface_0_fromEncoder_enable := false.B
  core.io_traceCoreInterface_0_fromEncoder_stall  := false.B

  // ---- Misc tie-offs ----
  core.io_sram_config := 0.U
  core.io_pll0_lock   := true.B
  core.nmi_0_0        := false.B
  core.nmi_0_1        := false.B
  // io_pll0_ctrl_0..5 are outputs, left unconnected.

  // ---- JTAG: tied off for first bring-up (open issue #12) ----
  // DTM held in reset; program loading uses serial-TL/LOADMEM, not JTAG.
  core.io_systemjtag_jtag_TCK    := false.B.asClock
  core.io_systemjtag_jtag_TMS    := false.B
  core.io_systemjtag_jtag_TDI    := false.B
  core.io_systemjtag_reset       := true.B.asAsyncReset
  core.io_systemjtag_mfr_id      := 0.U
  core.io_systemjtag_part_number := 0.U
  core.io_systemjtag_version     := 0.U
  // TDO_data, TDO_driven, io_debug_reset are outputs, left unconnected.
}