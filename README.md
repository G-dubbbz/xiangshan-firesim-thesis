# XiangShan–Chipyard–FireSim thesis

Repository for thesis-specific code, configurations, scripts, patches,
documentation, and reproducible experiment metadata.

## Environments
- Mac: editing, code review, small smoke tests, documentation
- IDUN: synthesis, large builds, FPGA/simulator jobs, benchmarks

## Upstream versions
Record exact Chipyard, FireSim, XiangShan, and toolchain revisions here.


## Dump for important information:

### Build Docker image:

```
cd ~/gustavo/programming/master/xiangshan-firesim-thesis

docker build \
  --platform linux/amd64 \
  --target base-with-firesim-metasim \
  -t chipyard-firesim-metasim \
  -f chipyard-dockerfiles/Dockerfile \
  chipyard-dockerfiles
```

### Run Docker image:
```
docker run --rm -it \
  --platform linux/amd64 \
  -v "${CHIPYARD_DIR}:/work/chipyard" \
  -v "${THESIS_DIR}:/work/thesis" \
  -v "${MASTER_ROOT}/xiangshan-work/XiangShan:/work/xiangshan" \
  -w /work \
  "${IMAGE}" "$@"
```
or run the script in:
```
./scripts/chipyard-shell.sh
```

### Run Docker alias
```
alias chipyard={Root/Path/To/Dir}/scripts/chipyard-shell.sh
```