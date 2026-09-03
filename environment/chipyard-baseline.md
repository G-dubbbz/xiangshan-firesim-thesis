# Chipyard local baseline

## Purpose

This baseline records the reproducible local environment used before
starting XiangShan-to-Chipyard integration.

The immediate project target is local Chipyard/Verilator integration.
FireSim deployment, IDUN execution, and large benchmarks are deferred.

## Source baseline

- Chipyard parent commit: `0acc1e1de2d3284bcd4d876956932a013ffe1949`
- Recursive submodule revisions: `chipyard-submodules.txt`
- Chipyard source mount: `/work/chipyard`
- Thesis repository mount: `/work/thesis`

## Container baseline

- Host OS: macOS
- Container runtime: Docker
- Container target platform: `linux/amd64`
- Docker image identity: `chipyard-firesim-metasim-image.txt`
- Tool environment: `/opt/conda/envs/chipyard`
- Container GLIBC: Ubuntu GLIBC 2.35

## Local compatibility patch

The patch:

```text
../patches/environment/chipyard-local-container-compat.patch
```

applies to a clean checkout at the pinned parent commit. Its SHA-256
checksum is recorded in:

```text
chipyard-local-container-compat.sha256
```

The patch changes only setup/dependency behavior:

- Uses Conda's `solver` key rather than the obsolete
  `experimental_solver` key for libmamba.
- Changes `sysroot_linux-64` from 2.34 to 2.35 to match the local
  Ubuntu GLIBC 2.35 container.
- Adds `--filter=blob:none` to selected submodule initialization
  commands.
- Preserves the upstream setup-script layout:

```text
build-setup.sh -> scripts/build-setup.sh
```

## Validation

The following completed successfully on 2026-09-02:

```bash
cd /work/chipyard
source env.sh
cd sims/verilator
make CONFIG=RocketConfig run-asm-tests
```

The stock `RocketConfig` RISC-V assembly-test suite completed using
Verilator. The final observed test was `rv64ui-v-subw`, which ended
with normal test-harness termination:

```text
Verilog $finish
```

The shell returned normally, with no fatal simulator, timeout, test, or
Make failure reported.

## Recreate and validate

1. Build the Docker image from `chipyard-dockerfiles/Dockerfile`.
2. Launch the container with `scripts/chipyard-shell.sh`.
3. Check out Chipyard at the pinned commit and initialize its recorded
   submodule revisions.
4. Apply the compatibility patch:

   ```bash
   git apply /work/thesis/patches/environment/chipyard-local-container-compat.patch
   ```

5. Source the environment and rerun:

   ```bash
   cd /work/chipyard
   source env.sh
   cd sims/verilator
   make CONFIG=RocketConfig run-asm-tests
   ```