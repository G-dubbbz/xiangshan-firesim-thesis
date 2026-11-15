# Microbenchmarks: XiangShan vs BOOM in Chipyard Verilator

This directory runs the "Extremely Simple Microbenchmarks" (Vertical Research Group,
UW–Madison; pinned commit in `microbench-commit.txt`) on XiangShan Minimal, Medium BOOM
and Mega BOOM in the same Chipyard SoC (direct Verilator, DRAMSim2), and plots the results.
All 40 benchmarks of the suite are run.

| File | Purpose |
|---|---|
| `rt/build.sh` | Compiles each benchmark once and links it into two runtimes: `build/htif/` (Spike, BOOM) and `build/xs/` (XiangShan). Freezes random input data in `rt/randarr/`. |
| `rt/run-spike.sh` | Spike reference run → `spike-results.txt` (the instruction counts every core must match), plus a rough runtime estimate. |
| `rt/run-all-xs.sh` | Runs the benchmarks on XiangShan → `results/xs/`. |
| `rt/run-all-htif.sh` | Runs the benchmarks on a BOOM config → `results/<CONFIG>/`. |
| `rt/compare.py` | Combines everything into `results/comparison.csv`, checking every instruction count against Spike. |
| `rt/plot_results.py` | Figures and `summary.txt` in `results/plots/`. |
| `rt/common.h`, `rt/rt_*.c`, `rt/crt_xs.S`, `rt/link_xs.ld` | ROI measurement (`mcycle`/`minstret`, fenced) and the two runtimes. |
| `rt/calib/` | Calibration of the ROI measurement. |

All commands run **inside the container on an IDUN compute node** (see `docs/REPRODUCE.md`
§2), except `git push`/`pull`, which run in the host shell. `$THESIS` is the repository
(`export THESIS="$HOME/xiangshan-firesim-thesis"`).

---

## 1. Allocate a node that can run all three cores at once

Every simulator is single-threaded, so CPUs = parallel benchmarks. With 24 CPUs, the
XiangShan and both BOOM runs can go side by side. Check the maximum time limit first:

```bash
sinfo -p CPUQ -o '%P %l'          # maximum wall time of the partition
```

Run everything inside `tmux` **on the login node**, so the allocation survives a lost
connection (`tmux new -s bench`; reattach later with `tmux attach -t bench`):

```bash
salloc --account=share-ie-idi --partition=CPUQ --nodes=1 \
  --cpus-per-task=24 --mem=96G --time=24:00:00 --job-name=microbench
ssh <node reported by salloc>
```

Then build the sandbox and enter the container as in `docs/REPRODUCE.md` §2.3–2.4.
In tmux, open one window per run (`Ctrl-b c`), each in the container.

## 2. Check the prerequisites

```bash
cd $THESIS/benchmarks
git -C microbench rev-parse HEAD; cat microbench-commit.txt      # must be identical
ls -l /work/chipyard/sims/verilator/simulator-chipyard.harness-{XiangShanConfig,MediumBoomV3Config,MegaBoomV3Config}
command -v spike riscv64-unknown-elf-gcc
```

If `microbench/` is missing:
`git clone https://github.com/VerticalResearchGroup/microbench microbench && git -C microbench checkout $(cat microbench-commit.txt)`.

## 3. Archive the previous results

The earlier runs covered 23 benchmarks. Keep them, so the new run can be checked against
them:

```bash
cd $THESIS/benchmarks
mkdir -p results/archive-23
mv results/xs results/MediumBoomV3Config results/MegaBoomV3Config \
   results/comparison.csv results/xs-results.csv results/*-meta.txt results/plots \
   results/archive-23/ 2>/dev/null; ls results/archive-23
```

## 4. Build all benchmarks

```bash
bash rt/build.sh 2>&1 | tee build.log | tail -3     # expect "40/40 built"
grep -E 'FROZE|WARN|FAILED' build.log
```

- `FROZE new random input data: rt/randarr/CRm.h` is expected once: CRm has random input
  data that wasn't frozen yet. Commit that file and `rt/randarr/SHA256SUMS` (step 9).
- There must be no `WARN` (kernel code differs between the two runtimes) or `FAILED`.
- `MM: htif heap 16781272 bytes` and the same for MM_st are expected: these two allocate
  ~6 MB with `malloc`, more than the 128 KB heap of the HTIF runtime, so their HTIF binaries
  are linked with a 16 MB heap (see the comment at the top of `rt/build.sh`).
- `SET=23` builds only the original 23; `BENCHES="CRd MM"` builds only those.

The build is deterministic, so the 23 earlier binaries come out identical (step 8 checks
this through the results).

## 5. Spike reference

```bash
bash rt/run-spike.sh | tail -14
git diff --stat spike-results.txt     # should only ADD lines; the 23 old counts must not change
```

Every line must contain `ROI cycles=… instret=…`; a `FAIL/timeout` line needs fixing first.
The table at the end is a **rough** estimate of the XiangShan run time per benchmark
(~1 cycle per instruction, ~1,300 cycles/s; heap setup not counted). From the source code,
**CRd** is expected to dominate (about 6 million recursive calls, so tens of millions of
instructions, which may take most of a day on XiangShan). If its estimate is longer than
your allocation, run it in its own longer allocation with `BENCHES=CRd` (step 6), or ask
your supervisor whether a reduced iteration count is acceptable.

## 6. Run the three cores

One tmux window each (all in `$THESIS/benchmarks`, inside the container):

```bash
J=8 bash rt/run-all-xs.sh                                    # XiangShan
CONFIG=MediumBoomV3Config J=8 bash rt/run-all-htif.sh        # Medium BOOM
CONFIG=MegaBoomV3Config   J=8 bash rt/run-all-htif.sh        # Mega BOOM
```

With 8 CPUs instead of 24, run them one after another with `J=8`.

What the run scripts do:

- **Longest first:** benchmarks are started in order of Spike instruction count plus
  `.bss` size, so the long ones don't end up running alone at the end.
- **Cycle limit:** `max(MIN_CYCLES, 24 × Spike instret)` harness cycles (default
  `MIN_CYCLES=60000000`). Passing runs stop on their own; the limit only matters if a
  run hangs.
- **Resuming:** `SKIP_PASSED=1` (default) skips a benchmark whose last run passed with the
  same ELF (sha256 in `results/<core>/<B>.elf.sha256`). If the allocation ends, rerun the
  same command and only the missing and failed benchmarks run. `SKIP_PASSED=0` reruns all.
- **Subsets:** `BENCHES="CRd"` runs only the listed benchmarks.
- Each run prints `done <B>  pass|FAIL  rc=…  <seconds>  limit=…` and appends it to
  `results/<core>/runs.txt`; `meta.txt` records commits and simulator for every run.

A `FAIL` with `TestDriver.v:147` in `results/<core>/<B>.log` is a timeout: rerun that
benchmark with a higher limit, e.g. `BENCHES=MM MIN_CYCLES=200000000 bash rt/run-all-xs.sh`.

## 7. Combine the results

```bash
python3 rt/parse_xs.py > results/xs-results.csv
python3 rt/compare.py MediumBoomV3Config MegaBoomV3Config > results/comparison.csv
grep -cE '^[A-Za-z]' results/comparison.csv           # 41 = header + 40 benchmarks
grep -E 'FAIL|INSTRET' results/comparison.csv || echo "all cells OK"
```

Every cell must be a number. `FAIL` means no result; `INSTRET-<n>` means the core executed
a different number of instructions than Spike, which must be investigated before the
result is used.

## 8. Check that the 23 earlier results reproduce

```bash
python3 - <<'EOF'
import csv
old = {r['bench']: r for r in csv.DictReader(open('results/archive-23/comparison.csv'))}
new = {r['bench']: r for r in csv.DictReader(open('results/comparison.csv'))}
d = [(b, k, old[b][k], new[b].get(k)) for b in old for k in old[b]
     if k.endswith('_cycles') and old[b][k] != new[b].get(k)]
print('all 23 earlier results reproduced exactly' if not d else d)
EOF
```

Same binaries and same simulators give the same cycle counts, so any difference means
something changed (binary, simulator or setup) and needs explaining.

## 9. Plot and commit

```bash
python3 -c "import matplotlib" 2>/dev/null || pip install --user matplotlib
python3 rt/plot_results.py --caveat-memory "Memory benchmarks: XiangShan also has Chipyard's 512 KB L2 behind its own L2/L3."
cat results/plots/summary.txt
```

This writes `results/plots/normalized_ipc.pdf` (IPC normalized to Medium BOOM = 1, bars
from 0, order Medium BOOM, XiangShan, Mega BOOM, one harmonic mean per category),
`ipc.pdf` (absolute IPC) and `summary.txt` (harmonic and arithmetic means per category,
normalized IPC per benchmark). Options: `--mean arithmetic`, `--ymax 0` (no cut-off bars),
`--order`, `--baseline`; see `python3 rt/plot_results.py --help`. If matplotlib can't be
installed on IDUN, copy `results/comparison.csv` and `rt/plot_results.py` to another
machine and run it there.

Then, in the host shell (no SSH in the container):

```bash
cd ~/xiangshan-firesim-thesis/benchmarks
for c in xs MediumBoomV3Config MegaBoomV3Config; do cp results/$c/meta.txt results/$c-meta.txt; done
git add -u results                      # tracked files that moved to archive-23 or changed
git add rt/randarr spike-results.txt results/*.csv results/*-meta.txt results/plots \
        results/archive-23/*.csv results/archive-23/*-meta.txt results/archive-23/plots
git status --short | head -30           # only CSVs, meta files, plots, randarr, spike-results
git commit -m "microbench: all 40 benchmarks on XiangShan, Medium and Mega BOOM"
git push
```

`build/`, the raw logs and `microbench/` are not committed; the commit pins, frozen input
data, Spike counts, CSVs and metadata are enough to reproduce the results.
