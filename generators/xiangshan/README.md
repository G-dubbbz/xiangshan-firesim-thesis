# generators/xiangshan — setup & staging steps

This is a **v0 draft** of the Verilog-blackbox Chipyard tile for XiangShan.
None of it has been compiled against a real Chipyard/rocket-chip checkout
yet. Treat the first `sbt compile` as the next debugging step, and work
through its errors the same incremental way the rest of this project's
open issues were resolved (see `xiangshan-baseline.md` in the "Project
thesis" Claude Project).

## 1. Register the project in Chipyard's `build.sbt`

Per the baseline doc's "Chipyard build baseline" section, add a
`lazy val xiangshan` project the same way `cva6`/`ibex` are added, and
list it in `chipyard`'s `.dependsOn(...)`:

```scala
lazy val xiangshan = (project in file("generators/xiangshan"))
  .dependsOn(rocketchip, rocketMacros)
  .settings(commonSettings)
```

Then add `xiangshan` to the `chipyard` project's own dependency list.

## 2. Stage the generated Verilog

From a **clean** XiangShan build (per the baseline doc's open issue #5 —
do not reuse a `build/rtl` directory shared with a `sim-verilog`/`emu`
run):

```bash
cd "$NOOP_HOME"
rm -rf build
make verilog CONFIG=MinimalConfig -j8
```

Then copy every `.sv`/`.v` file from `build/rtl` **except** `SimTop.sv`,
`SimTop.sv.conf`, `SimTop.fir` into this generator's resource directory:

```bash
mkdir -p generators/xiangshan/src/main/resources/vsrc
find build/rtl -maxdepth 1 -type f \( -name '*.sv' -o -name '*.v' \) \
  ! -name 'SimTop.sv' \
  -exec cp {} generators/xiangshan/src/main/resources/vsrc/ \;
```

(The 22 `Difftest*.v` files and `DummyDPICWrapper_*.sv` files ARE included
here deliberately — confirmed safe no-ops when `DIFFTEST` is undefined,
baseline doc open issue #6.)

Then generate the matching `addResource(...)` lines for
`XSTopBlackBox.scala` (there will be hundreds — do not hand-write them):

```bash
find generators/xiangshan/src/main/resources/vsrc -maxdepth 1 -type f \
  -printf '  addResource("/vsrc/%f")\n' | sort
```

Paste that output in to replace the two placeholder `addResource` lines
at the bottom of `XSTopBlackBox.scala`.

## 3. Fill in the two TODO port blocks

Two aggregate bundles in `XSTopBlackBox.scala` are stubbed out because
their exact flattened Verilog field names were never individually
enumerated in this session's research (everything else on the port list
*was* confirmed against the uploaded `XSTop.sv`):

```bash
grep -oE 'io_cacheable_check_[a-zA-Z0-9_]+' build/rtl/XSTop.sv | sort -u
grep -oE 'io_traceCoreInterface_0_[a-zA-Z0-9_]+' build/rtl/XSTop.sv | sort -u
```

Paste the output back so the blackbox IO and the tie-off/wiring code in
`XiangShanTileModuleImp` can be completed exactly, rather than guessed.

## 4. Also worth a quick sanity grep before first compile

The AXI4 channel field names in `XSTopBlackBox.scala`
(`io_memory_aw_ready`, etc.) follow the standard `VerilogAXI4Record`
flattening convention but were not individually diffed against XSTop.sv
line-by-line in this session:

```bash
grep -oE 'io_(memory|peripheral|dma)_[a-z_]+' build/rtl/XSTop.sv | sort -u
```

If any of these don't match, it's a fast, mechanical rename in
`XSTopBlackBox.scala` — not a design problem.

## 5. First validation target

Per the project's required validation strategy: do **not** jump to
FireSim or FPGA from here. First target is elaborating this config in
direct Chipyard Verilator (`sims/verilator`) far enough to get a clean
`chisel3`/`firrtl` elaboration + Verilator compile — a minimal bare-metal
boot is the step after that, once elaboration itself is clean.
