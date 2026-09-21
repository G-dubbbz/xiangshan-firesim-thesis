// XSTopBlackBox.scala
//
// Verilog-blackbox wrapper for XiangShan's generated `XSTop` module.
// Source of truth for every port below: `xiangshan-baseline.md` in the
// "Project thesis" Claude Project, section "`XSTop` port list — Verified",
// itself read directly from the user's generated `XSTop.sv`
// (MinimalConfig, XiangShan commit 18d7a15f4a8997f0ab1941bee0dc23b36ed97d3a,
// firtool-1.62.1, `--split-verilog`).
//
// STATUS PER SECTION:
//   [CONFIRMED]        — literal signal exists exactly as named in XSTop.sv,
//                         per the baseline doc.
//   [PATTERN, VERIFY]  — name follows a well-known rocket-chip/firtool
//                         flattening convention (VerilogAXI4Record for AXI4,
//                         standard SystemJTAGIO for JTAG) but the *exact*
//                         flattened string was not individually confirmed
//                         against XSTop.sv in this session. Verify with:
//                           grep -oE 'io_[A-Za-z0-9_]+' build/rtl/XSTop.sv | sort -u
//                         and diff against this file before first compile.
//   [TODO — NEEDS GREP] — the aggregate bundle's internal field names were
//                         never individually enumerated in the baseline doc
//                         (io_cacheable_check_*, io_traceCoreInterface_0_*).
//                         Do NOT trust the stub below; it will not compile
//                         (or worse, will compile against wrong ports) until
//                         replaced with the literal names from XSTop.sv.
//
// Run this to get the definitive full port list any time this file needs
// re-validation against a fresh `make verilog` output:
//   grep -E '^\s*(input|output)' build/rtl/XSTop.sv

package xiangshan

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, IntParam, StringParam}

/** One flattened AXI4 master-facing port as XiangShan/firtool emit it
  * (`VerilogAXI4Record`-style: `<prefix>_aw_*`, `_w_*`, `_b_*`, `_ar_*`,
  * `_r_*`, no `region`/`atop`/`user` fields — confirmed absent from
  * XSTop.sv per the baseline doc). [PATTERN, VERIFY exact prefix]
  *
  * Instantiated once from XSTop's perspective as a *master* (`memory_*`,
  * `peripheral_*`) and once as a *slave* (`dma_*`) — the direction is
  * flipped by which half (aw/w/ar = requests, b/r = responses) is input
  * vs. output at the XSTop boundary, handled by the two variants below
  * rather than a single `Flipped(...)`-parameterized bundle, so the
  * BlackBox IO stays a flat, unambiguous list of `Input`/`Output`.
  */
private class AXI4FlatMasterPort(addrW: Int, dataW: Int, idW: Int) extends Bundle {
  // Write address channel
  val aw_ready       = Input(Bool())
  val aw_valid       = Output(Bool())
  val aw_bits_id     = Output(UInt(idW.W))
  val aw_bits_addr   = Output(UInt(addrW.W))
  val aw_bits_len    = Output(UInt(8.W))
  val aw_bits_size   = Output(UInt(3.W))
  val aw_bits_burst  = Output(UInt(2.W))
  val aw_bits_lock   = Output(Bool())
  val aw_bits_cache  = Output(UInt(4.W))
  val aw_bits_prot   = Output(UInt(3.W))
  val aw_bits_qos    = Output(UInt(4.W))
  // Write data channel
  val w_ready        = Input(Bool())
  val w_valid        = Output(Bool())
  val w_bits_data    = Output(UInt(dataW.W))
  val w_bits_strb    = Output(UInt((dataW / 8).W))
  val w_bits_last    = Output(Bool())
  // Write response channel
  val b_ready        = Output(Bool())
  val b_valid        = Input(Bool())
  val b_bits_id      = Input(UInt(idW.W))
  val b_bits_resp    = Input(UInt(2.W))
  // Read address channel
  val ar_ready       = Input(Bool())
  val ar_valid       = Output(Bool())
  val ar_bits_id     = Output(UInt(idW.W))
  val ar_bits_addr   = Output(UInt(addrW.W))
  val ar_bits_len    = Output(UInt(8.W))
  val ar_bits_size   = Output(UInt(3.W))
  val ar_bits_burst  = Output(UInt(2.W))
  val ar_bits_lock   = Output(Bool())
  val ar_bits_cache  = Output(UInt(4.W))
  val ar_bits_prot   = Output(UInt(3.W))
  val ar_bits_qos    = Output(UInt(4.W))
  // Read data channel
  val r_ready        = Output(Bool())
  val r_valid        = Input(Bool())
  val r_bits_id      = Input(UInt(idW.W))
  val r_bits_data    = Input(UInt(dataW.W))
  val r_bits_resp    = Input(UInt(2.W))
  val r_bits_last    = Input(Bool())
}

/** Same five channels, directions flipped — for `dma_*`, where XSTop is the
  * AXI4 *slave* (an external master drives requests in). [PATTERN, VERIFY]
  */
private class AXI4FlatSlavePort(addrW: Int, dataW: Int, idW: Int) extends Bundle {
  val aw_ready       = Output(Bool())
  val aw_valid       = Input(Bool())
  val aw_bits_id     = Input(UInt(idW.W))
  val aw_bits_addr   = Input(UInt(addrW.W))
  val aw_bits_len    = Input(UInt(8.W))
  val aw_bits_size   = Input(UInt(3.W))
  val aw_bits_burst  = Input(UInt(2.W))
  val aw_bits_lock   = Input(Bool())
  val aw_bits_cache  = Input(UInt(4.W))
  val aw_bits_prot   = Input(UInt(3.W))
  val aw_bits_qos    = Input(UInt(4.W))

  val w_ready        = Output(Bool())
  val w_valid        = Input(Bool())
  val w_bits_data    = Input(UInt(dataW.W))
  val w_bits_strb    = Input(UInt((dataW / 8).W))
  val w_bits_last    = Input(Bool())

  val b_ready        = Input(Bool())
  val b_valid        = Output(Bool())
  val b_bits_id      = Output(UInt(idW.W))
  val b_bits_resp    = Output(UInt(2.W))

  val ar_ready       = Output(Bool())
  val ar_valid       = Input(Bool())
  val ar_bits_id     = Input(UInt(idW.W))
  val ar_bits_addr   = Input(UInt(addrW.W))
  val ar_bits_len    = Input(UInt(8.W))
  val ar_bits_size   = Input(UInt(3.W))
  val ar_bits_burst  = Input(UInt(2.W))
  val ar_bits_lock   = Input(Bool())
  val ar_bits_cache  = Input(UInt(4.W))
  val ar_bits_prot   = Input(UInt(3.W))
  val ar_bits_qos    = Input(UInt(4.W))

  val r_ready        = Input(Bool())
  val r_valid        = Output(Bool())
  val r_bits_id      = Output(UInt(idW.W))
  val r_bits_data    = Output(UInt(dataW.W))
  val r_bits_resp    = Output(UInt(2.W))
  val r_bits_last    = Output(Bool())
}

/** BlackBox for the generated `XSTop` Verilog module.
  *
  * IMPORTANT: `class` name and every `val` name below must byte-for-byte
  * match `module XSTop(...)`'s declared port names in XSTop.sv — Chisel
  * BlackBox IO binds by *name*, not position. Chisel's `IO()` for a
  * BlackBox uses the bundle field name directly as the port name (no
  * `io_` prefix auto-added the way normal modules get one) — the `io_`
  * prefix that appears throughout the doc and XSTop.sv is XiangShan's own
  * top-level IO bundle name ("io") baked in from its own module, i.e. the
  * literal Verilog port really is `io_extIntrs`, `io_rtc_clock`, etc. To
  * match that, every field below is named with the literal `io_...`
  * string using backticks, exactly like CVA6's blackbox wrapper does.
  */
class XSTop extends BlackBox with HasBlackBoxResource {
  // ---- AXI4 ports [CONFIRMED widths/direction; PATTERN, VERIFY exact flattened field names] ----
  val io_memory     = IO(new AXI4FlatMasterPort(addrW = 48, dataW = 256, idW = 14))
  val io_peripheral = IO(new AXI4FlatMasterPort(addrW = 31, dataW = 64,  idW = 2))
  val io_dma        = IO(new AXI4FlatSlavePort (addrW = 48, dataW = 256, idW = 14))

  // NOTE: the three AXI4 bundles above will NOT compile as-is against a
  // literal Verilog module — Chisel flattens `io_memory.aw_ready` to a
  // port named `io_memory_aw_ready`, which needs to *exactly* match
  // XSTop.sv. If XSTop.sv instead prefixes with an index (e.g.
  // `io_memory_0_aw_ready`), rename the outer IO() vals accordingly
  // (`io_memory_0`, etc.) after checking with:
  //   grep -oE 'io_(memory|peripheral|dma)_[a-z_]+' build/rtl/XSTop.sv | sort -u | head -40

  // ---- Clock / reset ----
  // XSTop resynchronizes its own reset internally (ResetGen(), confirmed
  // in top/Top.scala) — drive this with an ordinary (a)synchronous reset,
  // no external synchronizer needed on the Chipyard side. [CONFIRMED]
  val clock = IO(Input(Clock()))
  val reset = IO(Input(AsyncReset()))

  // Separate RTC/timer clock domain — distinct from `clock`. A real clock
  // source (not a data signal) must drive this; do not tie to 0.
  // [CONFIRMED existence + purpose, PATTERN for Chisel type: Clock()]
  val io_rtc_clock = IO(Input(Clock()))

  // ---- Single-hart control/status [CONFIRMED names + widths, suffix _0] ----
  val io_riscv_rst_vec_0          = IO(Input(UInt(48.W)))
  val io_riscv_halt_0             = IO(Output(Bool()))
  val io_riscv_critical_error_0   = IO(Output(Bool()))

  // External interrupt vector into XiangShan's own internal PLIC.
  // bit i (0-indexed) -> XiangShan-internal PLIC source (i+1); source 65
  // is reserved internally for the bus-error-unit and unreachable here.
  // [CONFIRMED — build/XSTop.dts, see baseline doc open issue #2]
  val io_extIntrs = IO(Input(UInt(64.W)))

  // ---- Cacheable-check port (TLPMAIO) ----
  // [TODO — NEEDS GREP] Confirmed *semantically* dead — no internal
  // consumer anywhere in XSTop/SoCMisc/the cores (top/Top.scala:
  // `misc.module.cacheable_check <> io.cacheable_check`, pure pass-through,
  // baseline doc "io_cacheable_check_* — fully resolved"). Safe to tie
  // req.valid=0 and leave resp unconnected — BUT the exact flattened
  // field names (this is a `TLPMAIO`, likely req_{valid,ready,bits_addr,
  // bits_size,bits_cmd,...}/resp_{...}, doubled for indices 0 and 1) were
  // never individually read from XSTop.sv in this session. Get them with:
  //   grep -oE 'io_cacheable_check_[a-zA-Z0-9_]+' build/rtl/XSTop.sv | sort -u
  // and replace this placeholder block before this file will compile.
  //
  // Placeholder (WILL NOT COMPILE — delete once real names are in hand):
  // val io_cacheable_check_req_0_valid  = IO(Output(Bool()))
  // val io_cacheable_check_req_1_valid  = IO(Output(Bool()))
  // ... (fill in from grep output)

  // ---- JTAG (standard rocket-chip SystemJTAGIO shape) [PATTERN, VERIFY] ----
  val io_systemjtag_jtag_TCK       = IO(Input(Clock()))
  val io_systemjtag_jtag_TMS       = IO(Input(Bool()))
  val io_systemjtag_jtag_TDI       = IO(Input(Bool()))
  val io_systemjtag_jtag_TDO_data  = IO(Output(Bool()))
  val io_systemjtag_jtag_TDO_driven= IO(Output(Bool()))
  val io_systemjtag_reset          = IO(Input(AsyncReset()))
  val io_systemjtag_mfr_id         = IO(Input(UInt(11.W)))
  val io_systemjtag_part_number    = IO(Input(UInt(16.W)))
  val io_systemjtag_version        = IO(Input(UInt(4.W)))
  val io_debug_reset               = IO(Output(AsyncReset()))

  // ---- Trace interface ----
  // [TODO — NEEDS GREP] `TraceCoreInterface` has many fields (group,
  // iaddr, iretire, itype, cause, tval, priv, ...) never individually
  // enumerated from XSTop.sv in this session. Not needed for first
  // bring-up (baseline doc: "tie off or connect ... later"). Get exact
  // names with:
  //   grep -oE 'io_traceCoreInterface_0_[a-zA-Z0-9_]+' build/rtl/XSTop.sv | sort -u
  // and either wire them up or tie every *input* sub-field to 0 / leave
  // every *output* sub-field unconnected once known.

  // ---- Misc tie-offs for first bring-up [CONFIRMED names+widths] ----
  val io_sram_config    = IO(Input(UInt(16.W)))   // tie to 0 for simulation
  val io_pll0_lock      = IO(Input(Bool()))        // tie to 1
  val io_pll0_ctrl_0    = IO(Output(UInt(32.W)))
  val io_pll0_ctrl_1    = IO(Output(UInt(32.W)))
  val io_pll0_ctrl_2    = IO(Output(UInt(32.W)))
  val io_pll0_ctrl_3    = IO(Output(UInt(32.W)))
  val io_pll0_ctrl_4    = IO(Output(UInt(32.W)))
  val io_pll0_ctrl_5    = IO(Output(UInt(32.W)))
  val nmi_0_0           = IO(Input(Bool()))        // tie to 0 unless a real NMI source is wired
  val nmi_0_1           = IO(Input(Bool()))        // tie to 0 unless a real NMI source is wired

  // Stage EVERY .sv file from a clean `build/rtl` (not just XSTop.sv —
  // --split-verilog means every submodule is a separate file; see
  // baseline doc, "Build output directory") under
  // generators/xiangshan/src/main/resources/vsrc/, then list them all
  // here. A single addResource call per file; do this generation-time
  // (e.g. from an sbt task or a checked-in generated list) rather than
  // by hand once the file count is in the hundreds.
  addResource("/vsrc/XSTop.sv")
  // addResource("/vsrc/HuanCun_....sv")
  // addResource("/vsrc/CLINT.sv")
  // addResource("/vsrc/TLPLIC.sv")   // or whatever the generated PLIC module is named
  // addResource("/vsrc/DebugModule.sv")
  // ... one line per remaining file in the clean build/rtl listing,
  //     INCLUDING the 22 Difftest*.v and DummyDPICWrapper_*.sv files
  //     (confirmed safe no-ops when DIFFTEST is undefined — baseline doc
  //     open issue #6) but EXCLUDING SimTop.sv / SimTop.sv.conf / SimTop.fir
  //     (sim-verilog-only, not part of this module's dependency tree).
}
