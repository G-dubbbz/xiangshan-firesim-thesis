#!/usr/bin/env bash
set -euo pipefail

MASTER_ROOT="${HOME}/gustavo/programming/master"
CHIPYARD_DIR="${MASTER_ROOT}/chipyard-work/chipyard"
THESIS_DIR="${MASTER_ROOT}/thesis"
IMAGE="chipyard-firesim-metasim"

docker run --rm -it \
  --platform linux/amd64 \
  -v "${CHIPYARD_DIR}:/work/chipyard" \
  -v "${THESIS_DIR}:/work/thesis" \
  -v "${MASTER_ROOT}/xiangshan-work/XiangShan:/work/xiangshan" \
  -w /work \
  "${IMAGE}" "$@"
