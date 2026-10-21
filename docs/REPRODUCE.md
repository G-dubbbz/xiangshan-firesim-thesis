# Reproducing XiangShan in Chipyard (direct Verilator)

This guide rebuilds the XiangShan-in-Chipyard bring-up starting from this repository
only. It covers:

1. building the container image,
2. setting up an IDUN compute node,
3. generating XiangShan RTL,
4. creating the Chipyard checkout and installing the XiangShan tile,
5. elaborating and building the Verilator simulator,
6. running the bare-metal smoke test.

**Known-good end state (2026-10-11, IDUN):** `XiangShanConfig` elaborates and the
Verilator simulator builds. XiangShan boots from DRAM at `0x80000000` and prints
`Hello XiangShan!` on Chipyard's UART.

**Scope:** this covers **direct Chipyard Verilator** (`sims/verilator`) only.
FireSim metasimulation and U250 runs are not covered yet.

Conventions:

- *host shell* means the IDUN compute node outside the container.
- *container* means inside the Apptainer sandbox.
- Paths in the container: `/work` is `~/xiangshan-firesim-thesis/work` on the host,
  and `/thesis` is this repository (`~/xiangshan-firesim-thesis`).

---

## 0. What this repository provides

| Path | Purpose |
|---|---|
| `chipyard-dockerfiles/Dockerfile`, `chipyard-docker-entrypoint.sh` | Container image: Chipyard `0acc1e1` at `/opt/chipyard-dist`, Conda env, RISC-V toolchain, Verilator, FireSim metasim, Mill (for XiangShan). |
| `environment/` | Pinned Chipyard commit, submodule snapshot, image identity. |
| `external/xiangshan/baseline.txt` | XiangShan commit and submodule snapshot (see the commit warning in §3). |
| `generators/xiangshan/` | The Chipyard tile (Scala). The Verilog (`vsrc/`) is regenerated, not committed. |
| `configs/XiangShanConfigs.scala` | Top-level `chipyard.XiangShanConfig`. |
| `patches/chipyard/xiangshan-build-sbt.patch` | Registers the `xiangshan` sbt project in Chipyard's `build.sbt`. |
| `scripts/xs_install_into_chipyard.sh` | Copies the tile, the config and the `build.sbt` change into a Chipyard checkout. |
| `scripts/xs_stage_rtl.sh` | Copies XiangShan RTL into the generator, prefixes module names, regenerates `addResource` lines, and writes a provenance manifest. |
| `scripts/xs_prefix_modules.py`, `scripts/xs_sync_resources.py` | Helpers used by `xs_stage_rtl.sh`. |
| `tests/` (`hello.S`, `link.ld`, `Makefile`) | Bare-metal UART smoke test. |

---

## 1. Build the container image (once, on an x86-64 Docker host or a Mac)

From the repository root:

```bash
docker build \
  --platform linux/amd64 \
  --target base-with-firesim-metasim \
  -t chipyard-firesim-metasim \
  -f chipyard-dockerfiles/Dockerfile \
  chipyard-dockerfiles
```

Record the image identity, then export it for IDUN:

```bash
docker image inspect chipyard-firesim-metasim \
  --format 'ID={{.Id}}{{"\n"}}Created={{.Created}}{{"\n"}}Architecture={{.Architecture}}{{"\n"}}RepoTags={{json .RepoTags}}' \
  > environment/chipyard-firesim-metasim-image.txt

docker save chipyard-firesim-metasim:latest -o chipyard-firesim-metasim-amd64.tar   # ~23 GB
sha256sum chipyard-firesim-metasim-amd64.tar > chipyard-firesim-metasim-amd64.tar.sha256
rsync -P chipyard-firesim-metasim-amd64.tar* <user>@<idun-login>:~/xiangshan-firesim-thesis/
```

The image that produced the known-good result is recorded in
`environment/chipyard-firesim-metasim-image.txt`
(`sha256:9d537f45…`, tag `ghcr.io/g-dubbbz/chipyard-firesim-metasim:0acc1e1-amd64`).
A rebuild resolves Conda and apt packages again, so a new image may differ slightly.
Prefer the archived tar (or the GHCR image) for exact reproduction.

> **Known gap in the image:** it does not contain `fd`, which Chipyard's makefiles
> use to detect Scala changes. Until `fd-find` (conda-forge) is added to the image,
> follow the **stale-jar rule** in §5. Adding it changes the image identity, so
> record the new ID when you do.

---

## 2. IDUN: allocate a node and enter the container

### 2.1 Repository and workspace (once)

On an IDUN login node:

```bash
git clone https://github.com/G-dubbbz/xiangshan-firesim-thesis.git ~/xiangshan-firesim-thesis
mkdir -p ~/xiangshan-firesim-thesis/work      # persistent workspace; git-ignored
# put chipyard-firesim-metasim-amd64.tar(.sha256) in ~/xiangshan-firesim-thesis/
cd ~/xiangshan-firesim-thesis && sha256sum -c chipyard-firesim-metasim-amd64.tar.sha256
```

### 2.2 Allocate a compute node (every session)

```bash
salloc --account=share-ie-idi --partition=CPUQ --nodes=1 \
  --cpus-per-task=8 --mem=64G --time=10:00:00 --job-name=xiangshan
ssh <node reported by salloc>
export MAKEFLAGS="-j8"
```

Use at least 32 GB of memory. XiangShan RTL generation needs it, and 64 GB was used.

### 2.3 Build the node-local sandbox (every new allocation)

In the host shell:

```bash
cd ~/xiangshan-firesim-thesis
export APPTAINER_WORK_ROOT="/tmp/${USER}/apptainer-work-${SLURM_JOB_ID}"
export APPTAINER_TMPDIR="$APPTAINER_WORK_ROOT/tmp" APPTAINER_CACHEDIR="$APPTAINER_WORK_ROOT/cache"
export SANDBOX_TMP="$APPTAINER_WORK_ROOT/chipyard-firesim-metasim-amd64"
mkdir -p "$APPTAINER_TMPDIR" "$APPTAINER_CACHEDIR" logs
apptainer build --sandbox "$SANDBOX_TMP" "docker-archive:$PWD/chipyard-firesim-metasim-amd64.tar" \
  2>&1 | tee "logs/apptainer-sandbox-build-${SLURM_JOB_ID}.log"
```

Warnings about `EPERM on setxattr` are harmless if the build ends with `Build complete`.
Don't use a SIF: on IDUN, packing it produced an invalid ~37 KB file.

### 2.4 Enter the container

```bash
apptainer shell \
  --bind "$HOME/xiangshan-firesim-thesis/work:/work" \
  --bind "$HOME/xiangshan-firesim-thesis:/thesis" \
  "$SANDBOX_TMP"
```

Apptainer does not run the Docker entrypoint, so source it yourself:

```bash
source /usr/local/bin/chipyard-docker-entrypoint.sh
export LC_ALL=C.UTF-8     # silences perl locale warnings
set +H                    # stops bash history expansion from mangling '!' in pasted text
test -f /.singularity.d/Singularity && echo "inside container"
echo "RISCV=$RISCV"; verilator --version; riscv64-unknown-elf-gcc --version | head -1
```

Expected: `RISCV=/opt/conda/envs/chipyard/riscv-tools`, Verilator 5.022, gcc 13.2.0.

The `/thesis` bind is new and was not part of the 2026-10 runs. If Apptainer
refuses to create that mount point, leave it out and run the `scripts/` from the
host shell instead. They only need bash, python3 and git. Set
`CHIPYARD_DIR=~/xiangshan-firesim-thesis/work/chipyard` and
`XS_HOME=~/xiangshan-firesim-thesis/work/xs-env/XiangShan`.

On this system `which` rejects some invocations (`Illegal option --`).
Use `command -v <tool>` instead.

---

## 3. Generate XiangShan RTL

### 3.1 Create the XiangShan workspace (first time only)

The validated workspace is `/work/xs-env` (OpenXiangShan `xs-env`), pinned to:

```text
xs-env:    fe437b5719533ea52295e192bae0a3b40ee203f7
XiangShan: 16ae9ddcda54fc9a2fddffad73174cf793ac7814   (see warning below)
NEMU:      a48a11f208ff81ad765591d83271d1b339fb3861   (only needed for XiangShan's own emu/difftest)
nexus-am:  086a238062fdfb3281b745a537b361983d0154b5   (same)
```

> **Reconstructed, not recorded.** The exact commands that originally created
> `xs-env` were not saved. The outline below follows xs-env's normal flow.
> Verify it against the xs-env README at the pinned commit, and replace this block
> with the real commands once you have run them.
>
> ```bash
> cd /work
> git clone https://github.com/OpenXiangShan/xs-env.git && cd xs-env
> git checkout fe437b5719533ea52295e192bae0a3b40ee203f7
> # xs-env's setup script clones XiangShan, NEMU, nexus-am, DRAMsim3, ...
> # (skip any part that installs apt packages: the container already has the tools)
> cd XiangShan
> git checkout 16ae9ddcda54fc9a2fddffad73174cf793ac7814
> make init        # initializes XiangShan's submodules (a make-init.log exists locally)
> ```

> **⚠ Commit discrepancy to resolve.** `external/xiangshan/baseline.txt` records
> XiangShan `18d7a15f…` on `kunminghu-v3`. However, the IDUN runbook and the
> generated device tree (`model = "xiangshan,Kunminghu-dev- 16ae9ddc-DIRTY …"`)
> show that the RTL actually used came from `16ae9ddc…` **with uncommitted local
> changes**. Before trusting a rebuild, run this on IDUN:
>
> ```bash
> git -C /work/xs-env/XiangShan rev-parse HEAD
> git -C /work/xs-env/XiangShan status --short
> git -C /work/xs-env/XiangShan diff > /thesis/external/xiangshan/xiangshan-local.diff
> git -C /work/xs-env/XiangShan submodule status --recursive > /thesis/external/xiangshan/submodules-idun.txt
> ```
>
> Then update `baseline.txt`. `scripts/xs_stage_rtl.sh` records the commit and the
> dirty state automatically from now on (§4.3).

### 3.2 Generate `XSTop` RTL

```bash
cd /work/xs-env && source ./env.sh       # REQUIRED: sets NOOP_HOME (see note)
echo "NOOP_HOME=$NOOP_HOME"              # must be /work/xs-env/XiangShan
cd "$NOOP_HOME"
rm -rf build                             # REQUIRED: never mix with sim-verilog/emu output
make verilog CONFIG=MinimalConfig -j8 2>&1 | tee /work/xs-verilog-$(date +%Y%m%d-%H%M).log
```

Check the result:

```bash
test -s build/rtl/XSTop.sv && echo "XSTop.sv OK"
test ! -e build/rtl/SimTop.sv && echo "no SimTop (clean)"
ls build/rtl/*.sv build/rtl/*.v | wc -l      # 2026-10: 1581
sha256sum build/rtl/XSTop.sv
```

Notes:

- If `NOOP_HOME` is unset, elaboration finishes but then crashes with
  `java.util.NoSuchElementException: NOOP_HOME` from `difftest...FileControl.write`.
- `make verilog` (with `--fpga-platform`, no difftest) is the right target.
  `Difftest*.v` files are still emitted, but compile to empty modules because
  `DIFFTEST` is not defined in Chipyard.
- Output is split into one file per module, so all ~1581 files are needed, not
  just `XSTop.sv`.

---

## 4. Chipyard checkout and XiangShan tile

### 4.1 Create `/work/chipyard` (first time only)

In the container, copy the image's prepared Chipyard tree. This is the method used
on IDUN, and it already contains the container-compatibility changes, so don't
apply `patches/environment/chipyard-local-container-compat.patch` to it.

```bash
cp -a /opt/chipyard-dist /work/chipyard          # large (tens of GB)
source /usr/local/bin/chipyard-docker-entrypoint.sh   # now selects /work/chipyard and refreshes its env.sh
echo "$CHIPYARD_WORKDIR"                         # /work/chipyard
git -C /work/chipyard rev-parse HEAD             # 0acc1e1de2d3284bcd4d876956932a013ffe1949
```

Optional baseline check: a Rocket smoke test (`cd /work/chipyard/sims/verilator &&
make CONFIG=RocketConfig run-asm-tests`), as recorded in
`environment/chipyard-baseline.md`.

### 4.2 Install the tile, config and `build.sbt` change

```bash
CHIPYARD_DIR=/work/chipyard /thesis/scripts/xs_install_into_chipyard.sh
```

This copies:

- `generators/xiangshan/src/main/scala/*` → `/work/chipyard/generators/xiangshan/...`
- `configs/XiangShanConfigs.scala` → `/work/chipyard/generators/chipyard/src/main/scala/config/`

It then applies `patches/chipyard/xiangshan-build-sbt.patch`, unless `build.sbt`
already defines `lazy val xiangshan`. The patch makes two edits:

1. adds `xiangshan,` to `baseProjects` in `lazy val chipyard`, so the always-on
   dependency list includes it. It's a plain directory, not a git submodule, so it
   cannot go in `optionalModules`;
2. adds the project definition:
   ```scala
   lazy val xiangshan = (project in file("generators/xiangshan"))
     .dependsOn(rocketchip)
     .settings(libraryDependencies ++= rocketLibDeps.value)
     .settings(commonSettings)
   ```

The script is idempotent and backs up any file it overwrites (`*.bak-<timestamp>`).
**The repository is the source of truth.** Edit the Scala here and re-run the
script. Don't edit `/work/chipyard/generators/xiangshan` directly, or copy your
changes back before re-running.

### 4.3 Stage the RTL

```bash
CHIPYARD_DIR=/work/chipyard XS_HOME=/work/xs-env/XiangShan /thesis/scripts/xs_stage_rtl.sh
```

This:

- refuses a mixed `build/rtl` that contains `SimTop.sv`;
- writes `generators/xiangshan/xs-rtl-manifest.txt` (XiangShan commit, dirty flag,
  submodules, sha256 of every source file) plus `xiangshan-local.diff` if dirty;
- replaces `vsrc/`;
- prefixes every module except `XSTop` with `XS_`;
- rewrites the `addResource` list in `XSTopBlackBox.scala`.

Expected output (2026-10): `1581 files, 1580 modules defined, renaming 1579`,
`rewrote 1579 definitions, 4809 instantiations`, then 1581 `addResource` lines.
Copy the manifest into the repository with your results:

```bash
cp /work/chipyard/generators/xiangshan/xs-rtl-manifest.txt /thesis/external/xiangshan/
```

**Why prefixing is required:** Chipyard writes firtool output and blackbox
resources into one `gen-collateral/` directory, one file per module. XiangShan's
RTL shares 58 module names with Chipyard's, for example HuanCun vs InclusiveCache
`SourceD`/`MSHR`, and rocket-chip `AXI4Fragmenter`/`CLINT`/`Queue1_*` generated by
XiangShan's own rocket-chip fork. Without prefixing, same-named files silently
overwrite each other, and Verilator fails with `%Error-PINNOTFOUND` in
`InclusiveCacheBankScheduler.sv`, or worse, compiles the wrong module.
**Re-stage every time XiangShan RTL is regenerated.**

---

## 5. Elaborate and build the simulator

> **Stale-jar rule.** Because `fd` is missing from the image, `make` does not notice
> Scala or resource changes and silently reuses `.classpath_cache/chipyard.jar`.
> After **any** install or stage step, run:
> `rm -f /work/chipyard/.classpath_cache/*.jar`. After re-staging RTL, also delete
> the config's `generated-src` directory. A correct run shows
> `Built: /work/chipyard/.classpath_cache/chipyard.jar` in the log.

```bash
cd /work/chipyard && rm -f .classpath_cache/*.jar
cd sims/verilator
rm -rf generated-src/chipyard.harness.TestHarness.XiangShanConfig
make CONFIG=XiangShanConfig verilog 2>&1 | tee /work/xs-elab-$(date +%Y%m%d-%H%M).log
LOG=$(ls -t /work/xs-elab-*.log | head -1)
grep -nE 'Built:|<= xiangshan|Exception|Caused by|OutOfMemory|requirement failed|error:' "$LOG" | head -40
```

Judge the run by the `grep`, not by `tail`. The jar build runs in parallel and can
print `[success]` after the generator has already crashed.

Expected in the log:

- `Built: …/chipyard.jar`;
- an L2 client map listing `xiangshan_tile_0_memory ID#0..15` and
  `xiangshan_tile_0_peripheral ID#0..3`;
- no `Caused by`.

Harmless noise you will also see:

- `fd: command not found`;
- perl locale warnings;
- `printf-encoded verification operation(s)` from firtool;
- `common.mk:288 … Error 1 (ignored)`;
- `Empty *.mems.conf`;
- `TLMonitor … source bits (15) > 14`.

Collision check. The expected output is exactly one line: `XSTop`.

```bash
G=generated-src/chipyard.harness.TestHarness.XiangShanConfig
grep -oE '^\s*(public )?(ext)?module [A-Za-z0-9_$]+' $G/*.fir | awk '{print $NF}' | sort -u > /tmp/cy_mods
comm -12 /tmp/cy_mods <(ls /work/chipyard/generators/xiangshan/src/main/resources/vsrc | sed -E 's/\.s?v$//' | sort -u)
```

Build the simulator. This took about 6 minutes at `-j8` and produced a 46 MB binary:

```bash
make CONFIG=XiangShanConfig -j8 2>&1 | tee /work/xs-vbuild-$(date +%Y%m%d-%H%M).log | grep -E '%Error|Caused by|Exception' | head
ls -lh simulator-chipyard.harness-XiangShanConfig
grep -c 'XS_' $G/sim_files.common.f                         # ~1580
grep -nE '^\s*XSTop\s' $G/gen-collateral/XiangShanTile.sv   # one instance line
```

---

## 6. Run the bare-metal smoke test

```bash
cd /thesis/tests && make            # builds xs-hello.elf, rejects LOAD segments below 0x80000000
cd /work/chipyard/sims/verilator
time make CONFIG=XiangShanConfig run-binary LOADMEM=1 TIMEOUT_CYCLES=1000000 \
  BINARY=/thesis/tests/xs-hello.elf
O=output/chipyard.harness.TestHarness.XiangShanConfig
grep -E 'rst_vec|periph|STALLED|ERROR|CRITICAL' $O/xs-hello.out | head -60   # [XSPROBE] debug probe
grep -n 'UART' $O/xs-hello.log; cat $O/xs-hello.log
```

Expected:

- `[XSPROBE] rst_vec=000080000000`.
- `mem AR addr=80000000 …`: instruction fetch from DRAM.
- `periph AW addr=10020008` (UART `txctrl`), followed by alternating `periph AR`
  (status poll) and `periph AW`/`W`, whose low bytes are the characters
  (`48 65 6c 6c 6f …`). Every write gets a `periph B … resp=0`.
- Console: `[UART] UART0 is here` followed by `Hello Xiang…`. The simulated UART
  needs about 90k cycles per character, so 1M cycles shows roughly 11 characters.
  Raise `TIMEOUT_CYCLES` to about 1.6M to see the whole string.
- The run **ends with `*** FAILED *** (timeout)` / `TestDriver.v:147` `$stop`.
  That is expected**: the test spins forever and does not signal an exit (see §8).
- Speed: about 1,300 target cycles/s (1M cycles ≈ 12.5 min).

Save the evidence in the repository:

```bash
mkdir -p /thesis/results/logs/$(date +%Y-%m-%d)-xs-hello
cp $O/xs-hello.out /thesis/results/logs/$(date +%Y-%m-%d)-xs-hello/xs-hello.out.txt
cp $O/xs-hello.log /thesis/results/logs/$(date +%Y-%m-%d)-xs-hello/xs-hello.console.txt
```

The `.txt` names matter because `.gitignore` excludes `*.log`.

---

## 7. Troubleshooting (all observed during bring-up)

| Symptom | Cause | Fix |
|---|---|---|
| A fixed error comes back unchanged | Stale `chipyard.jar` (no `fd`) | `rm -f /work/chipyard/.classpath_cache/*.jar` |
| `NoSuchElementException: NOOP_HOME` at the end of `make verilog` | `xs-env/env.sh` not sourced | `cd /work/xs-env && source env.sh` |
| `Ports cannot overlap: AddressSet(0x0, 0xffffffffffff) …` | A tile node claims the whole address space (old DMA slave node) | `dma_*` is tied idle in the current tile |
| `IndexOutOfBoundsException: 0` in `TLXbar` | Tile master xbar not routed out | Current tile has `tlOtherMastersNode := tlMasterXbar.node; masterNode :=* tlOtherMastersNode` |
| `OutOfMemoryError` with `source bits (18..22)` warnings | 14-bit AXI IDs blow up the per-ID adapters | Current tile uses `AXI4IdIndexer(4)` |
| `BlackBox … must have a port named 'io'` | `XSTop` declared as `BlackBox` | It is an `ExtModule` now |
| Verilator `%Error-PINNOTFOUND` in `InclusiveCacheBankScheduler.sv` | Module-name collisions (RTL not prefixed) | Re-run `xs_stage_rtl.sh`, clear jars and `generated-src` |
| `CRITICAL ERROR asserted` around cycle 2100 and no AXI traffic | Reset vector `0x10000` (bootrom) is not executable in XiangShan's PMA | Keep `resetVectorOverride = 0x80000000`, run with `LOADMEM=1` |
| Simulator segfaults within ~2 s | ELF `LOAD` segment below `0x80000000` (linked with `-Ttext` only) | Link with `tests/link.ld` (`tests/Makefile` checks this) |
| riscv-tests time out without touching `tohost` | Store buffer holds the `tohost` store up to 2²⁰ cycles, and XiangShan's caches are not coherent with the harness | Known limitation, see §8 |
| `unlink: … Read-only file system` after a run | Binary lives in the read-only toolchain dir | Copy the binary to `/work` or `/thesis` first |
| Commands fail on a new node | Sandbox is node-local | Redo §2.3–2.4; everything in `/work` persists |

---

## 8. Known limitations and open issues

- **Test termination:** `tohost` doesn't work yet.
  - XiangShan's store buffer evicts idle entries only after `EvictCycles = 1 << 20`
    cycles (`Sbuffer.scala:39`).
  - XiangShan's write-back L1/L2/L3 sit inside `XSTop`, behind non-coherent AXI,
    so the harness never sees `tohost` in DRAM.
  - Candidate fix: an uncached Chipyard scratchpad below `0x1000_0000`, with
    `tohost` linked there and a `fence` before the final store.
- **Boot path:** Chipyard's bootrom and TSI loading are bypassed. Moving back to
  them needs a XiangShan PMA change that makes `0x10000` executable, plus a fix for
  the bootrom's `wfi`/`msip` wake-up (XiangShan only sees its internal CLINT).
- **Tied off:**
  - `dma_*`: issue #15;
  - external interrupts (`io_extIntrs = 0`): #14;
  - JTAG/debug, and `io_rtc_clock = clock`: #12;
  - vector in the DTS (`useVector = false`).
- **Debug probe:** the `[XSPROBE]` printfs are compiled into the tile. Remove them
  or put them behind a parameter before FireSim or any benchmarking.
- **Performance tunables:** `AXI4IdIndexer(4)` and `AXI4UserYanker(Some(8))` cap
  outstanding memory requests at 128. Validate this against XiangShan's real MLP
  before comparing with BOOM.
- **Methodology:** XiangShan's internal 512 KB HuanCun L3 (MinimalConfig: 1024 sets x 8 ways x 1 bank; the 4 MB is the SoC.scala default it overrides) sits in front of
  Chipyard's L2 (double LLC). Resolve this before any BOOM comparison.

---

## 9. Reproducibility checklist (record per experiment)

- [ ] Thesis repo commit (`git -C /thesis rev-parse HEAD`)
- [ ] Image ID (`environment/chipyard-firesim-metasim-image.txt`) and tar sha256
- [ ] Chipyard commit, plus `git -C /work/chipyard status --short`. The expected
      changes are only `build.sbt`, the new config file, `generators/xiangshan/`
      and the entrypoint-rewritten `env.sh`.
- [ ] XiangShan commit, dirty diff and submodules (`xs-rtl-manifest.txt`)
- [ ] XiangShan `CONFIG` (`MinimalConfig`) and the exact `make verilog` command
- [ ] Logs: elaboration, Verilator build, run (`.out` and `.log`)
- [ ] Test ELF sha256 and the exact `run-binary` command line
