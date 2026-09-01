# Chipyard Docker Runbook

This runbook explains how to build the Chipyard Docker image and run it with an
editable Chipyard checkout mounted from the host.

The host does not need Conda, Verilator, the RISC-V toolchain, or FireSim
dependencies. Those live inside the Docker image. The host only needs Docker,
plus Git if you want to clone Chipyard directly on the host.

## 1. Required Files

Start from a directory that contains this repository's Docker files:

```text
dockerfiles/
  Dockerfile
  chipyard-docker-entrypoint.sh
```

Run the Docker build commands from the repository root, so the build context is
the `dockerfiles` directory.

## 2. Build the Docker Image

The default image includes standard Chipyard Verilator support and FireSim
Verilator metasim setup:

```bash
docker build \
  -f dockerfiles/Dockerfile \
  --target base-with-firesim-metasim \
  -t chipyard-firesim-metasim \
  dockerfiles
```

For a smaller image with standard Chipyard Verilator support only:

```bash
docker build \
  -f dockerfiles/Dockerfile \
  --target base-with-tools \
  -t chipyard-verilator \
  dockerfiles
```

During the build, Docker creates an image-owned Chipyard setup at:

```text
/opt/chipyard-dist
```

Do not mount anything over `/opt/chipyard-dist`. It is the container's fallback
Chipyard copy and holds the image-built setup.

## 3. Prepare a Host Chipyard Checkout

The normal workflow is to keep an editable Chipyard checkout on the host and
mount it into the container at:

```text
/work/chipyard
```

Most Chipyard build and simulation outputs are created inside the Chipyard tree.
Because `/work/chipyard` is a host bind mount, those outputs persist on the
host automatically.

### Option A: Create a Checkout from the Image

Copy the image's initialized Chipyard tree out to the host:

```bash
mkdir -p chipyard-work
cd chipyard-work

docker run --rm \
  -v "$PWD:/work" \
  chipyard-firesim-metasim \
  bash -lc 'cp -a /opt/chipyard-dist /work/chipyard && chown -R "$(stat -c "%u:%g" /work)" /work/chipyard'
```

This creates `./chipyard` on the host. The copy can be large because it comes
from the prepared image tree.

### Option B: Use an Existing Host Checkout

If you already have an initialized Chipyard checkout, use that path as the host side of the `/work/chipyard` mount when starting the
container.

### Option C: Clone a Fresh Checkout on the Host (Untested)

If the host has Git installed, create a fresh Chipyard checkout:

```bash
mkdir -p chipyard-work
cd chipyard-work

git clone https://github.com/ucb-bar/chipyard.git chipyard
cd chipyard
git checkout 0acc1e1de2d3284bcd4d876956932a013ffe1949
```

Return to the workspace directory before starting the container:

```bash
cd ..
```

This fresh clone is only the top-level Chipyard repository. After starting the
container in Step 4 with this checkout mounted at `/work/chipyard`, initialize
the mounted tree from inside the container:

```bash
cd /work/chipyard
source env.sh

./build-setup.sh \
  --skip-conda \
  --skip-toolchain \
  --skip-ctags \
  --skip-marshal \
  --skip-circt \
  --skip-clean
```

Run this inside the container, not on the host. The command reuses the
container's Conda environment and RISC-V toolchain, but initializes the fresh
host checkout's submodules and FireSim metasim setup.

## 4. Start the Container

Choose the host Chipyard checkout you want to use, then pass that path to
Docker as a bind mount.

Set this variable to your host Chipyard directory, e.g. the one just copied from the image if you followed Option A:


```bash
CHIPYARD_HOST_DIR="/path/to/chipyard"
```

`CHIPYARD_HOST_DIR` must point at the Chipyard checkout root itself, not at a
parent directory that contains a `chipyard` subdirectory. The selected directory
should contain `build-setup.sh`, `generators/`, and `sims/`.

Check the path before starting Docker:

```bash
test -f "$CHIPYARD_HOST_DIR/build-setup.sh"
test -d "$CHIPYARD_HOST_DIR/sims"
test -d "$CHIPYARD_HOST_DIR/generators"
```

If you later see paths like `/work/chipyard/chipyard/sims/...` inside the
container, the mount is one directory too high. Stop the container, set
`CHIPYARD_HOST_DIR` to the nested `chipyard` checkout directory, and start the
container again.

Start the default FireSim metasim-capable container:

```bash
docker run --rm -it \
  -v "$CHIPYARD_HOST_DIR:/work/chipyard" \
  chipyard-firesim-metasim
```

If you built the smaller Verilator-only image, use:

```bash
docker run --rm -it \
  -v "$CHIPYARD_HOST_DIR:/work/chipyard" \
  chipyard-verilator
```

In both commands, `/work/chipyard` should stay exactly as written. Only change
`CHIPYARD_HOST_DIR` to select a different host Chipyard tree.

The entrypoint script runs automatically whenever the container starts. Users do
not need to call or source it manually. It activates the container Conda
environment, sets `RISCV`, selects `/work/chipyard` when mounted, and refreshes
the mounted checkout's `env.sh` with container paths.

## 5. Check the Environment

Inside the container:

```bash
echo "$CHIPYARD_WORKDIR"
echo "$RISCV"
which verilator
which riscv64-unknown-elf-gcc
```

Expected paths:

```text
CHIPYARD_WORKDIR=/work/chipyard
RISCV=/opt/conda/envs/chipyard/riscv-tools
```

If `/work/chipyard` is not mounted or does not look like a Chipyard checkout,
the container falls back to `/opt/chipyard-dist`.

## 6. Work in Chipyard

There are two common Verilator paths:

- Standard Chipyard Verilator runs the target RTL directly from
  `sims/verilator`.
- FireSim Verilator metasim runs the FireSim/MIDAS-transformed design from
  `sims/firesim/sim`.

### Standard Chipyard Verilator

Use this flow for normal Chipyard RTL simulation:

```bash
cd /work/chipyard
source env.sh
CHIPYARD_DIR=$(git rev-parse --show-toplevel)
cd sims/verilator
```

Run a built-in RISC-V benchmark as a smoke test:

```bash
make CONFIG=RocketConfig \
  BINARY="$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/median.riscv" \
  run-binary
```

To run your own RISC-V binary, keep it anywhere under the mounted Chipyard tree
or under an optional extra mount. For example, if the binary is stored in
`/work/chipyard/local-binaries/myprog.riscv`:

```bash
make CONFIG=RocketConfig \
  BINARY="$CHIPYARD_DIR/local-binaries/myprog.riscv" \
  run-binary
```

For a different Chipyard config, change only `CONFIG`:

```bash
make CONFIG=SmallBoomConfig \
  BINARY="$CHIPYARD_DIR/local-binaries/myprog.riscv" \
  LOADMEM=1 \
  run-binary
```

Build products, generated RTL, simulator binaries, logs, and waveforms created
by these commands are written under `/work/chipyard/sims/verilator`, so they
persist on the host through the Chipyard bind mount.

### FireSim Verilator Metasim

Use this flow when you want Verilator to run the FireSim/MIDAS-transformed
design rather than the direct Chipyard target-level simulator.

Source FireSim's `env.sh`, then work from the FireSim `sim` directory:

```bash
cd /work/chipyard/sims/firesim
source env.sh
CHIPYARD_DIR=$(git -C ../.. rev-parse --show-toplevel)
cd sim
```

Example 1, run the small FireSim `midasexamples` GCD metasim as a quick smoke
test. This is not a RocketChip SoC; it is a small MIDAS example that checks the
FireSim Verilator metasim path:

```bash
make TARGET_PROJECT=midasexamples \
  DESIGN=GCD \
  TARGET_CONFIG=NoConfig \
  PLATFORM=f2 \
  run-verilator
```

Example 2, run a Rocket-based FireSim target with a built-in RISC-V benchmark:

```bash
make TARGET_PROJECT=firechip \
  TARGET_PROJECT_MAKEFRAG="$CHIPYARD_DIR/generators/firechip/chip/src/main/makefrag/firesim" \
  DESIGN=FireSim \
  TARGET_CONFIG=FireSimRocketConfig \
  PLATFORM_CONFIG=BaseF2Config \
  PLATFORM=f2 \
  SIM_BINARY="$RISCV/riscv64-unknown-elf/share/riscv-tests/benchmarks/median.riscv" \
  run-verilator
```

Example 2 can fail if you ran example 1 before it. Clean the sim directory in between runs:

```bash
make clean
rm -rf generated-src
rm -rf output
rm -rf *.log
```

In both examples, `run-verilator` builds the Verilator simulator first if it is
missing, then runs it.

For debug waveforms, use the debug run target:

```bash
make TARGET_PROJECT=midasexamples \
  DESIGN=GCD \
  TARGET_CONFIG=NoConfig \
  PLATFORM=f2 \
  run-verilator-debug
```

For your own FireSim target, use the same make targets but replace the
FireSim-specific variables, such as `TARGET_PROJECT`,
`TARGET_PROJECT_MAKEFRAG`, `DESIGN`, `TARGET_CONFIG`, and `PLATFORM`, with the
values used by your project.

FireSim metasim output is written under `/work/chipyard/sims/firesim/sim`, so
it also persists on the host through the Chipyard bind mount.

## 7. Optional Extra Mounts

The default run command only mounts the Chipyard checkout. That is enough for
most generated files because they are written under `/work/chipyard`.

Add extra mounts only when you intentionally want separate host directories for
inputs, logs, archives, or shared binaries. For example:

```bash
mkdir -p benchmarks logs

docker run --rm -it \
  -v "$CHIPYARD_HOST_DIR:/work/chipyard" \
  -v "$PWD/benchmarks:/work/benchmarks" \
  -v "$PWD/logs:/work/logs" \
  chipyard-firesim-metasim
```

Use these extra paths explicitly in your commands, such as
`BINARY=/work/benchmarks/myprog.riscv`, when you choose to mount them.

## 8. Notes

- The Docker image owns `/opt/conda`, `/opt/conda/envs/chipyard`, the RISC-V
  toolchain, Verilator, and the fallback `/opt/chipyard-dist` tree.
- The host owns the editable checkout mounted at `/work/chipyard`.
- Host Conda is not required and is not used by the container.
- Do not mount over `/opt/chipyard-dist`.
- If the entrypoint replaces `/work/chipyard/env.sh`, it backs up the previous
  file as `env.sh.host-backup.<timestamp>`.
- If a FireSim command reports
  `/work/chipyard/generators/.../config.mk: No such file or directory`, check
  whether the checkout is nested at `/work/chipyard/chipyard`. That means the
  host mount selected the parent directory instead of the Chipyard checkout
  root.
