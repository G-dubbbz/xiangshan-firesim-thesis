#!/usr/bin/env bash
# xs_install_into_chipyard.sh -- copy the thesis XiangShan integration into a
# Chipyard checkout:
#   generators/xiangshan/src/main/scala/**   -> $CHIPYARD_DIR/generators/xiangshan/...
#   configs/XiangShanConfigs.scala           -> $CHIPYARD_DIR/generators/chipyard/src/main/scala/config/
#   patches/chipyard/xiangshan-build-sbt.patch applied to $CHIPYARD_DIR/build.sbt (if not already)
#
# Does NOT stage RTL: run scripts/xs_stage_rtl.sh afterwards.
#
# The thesis repo is the source of truth. Files in Chipyard that differ are
# listed and overwritten (a timestamped backup is kept next to each one).
#
# Environment:
#   CHIPYARD_DIR  Chipyard checkout (default /work/chipyard)
set -euo pipefail

THESIS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CHIPYARD_DIR=${CHIPYARD_DIR:-/work/chipyard}
STAMP=$(date +%Y%m%d-%H%M%S)

die() { echo "ERROR: $*" >&2; exit 1; }
[ -f "$CHIPYARD_DIR/build-setup.sh" ] && [ -d "$CHIPYARD_DIR/generators" ] \
  || die "$CHIPYARD_DIR is not a Chipyard checkout root"

install_file() {  # install_file <src> <dst>
  local src=$1 dst=$2
  mkdir -p "$(dirname "$dst")"
  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    echo "  unchanged  ${dst#$CHIPYARD_DIR/}"
    return
  fi
  if [ -f "$dst" ]; then
    cp "$dst" "$dst.bak-$STAMP"
    echo "  UPDATED    ${dst#$CHIPYARD_DIR/}   (backup: $(basename "$dst").bak-$STAMP)"
  else
    echo "  new        ${dst#$CHIPYARD_DIR/}"
  fi
  cp "$src" "$dst"
}

echo "Installing XiangShan integration from $THESIS_DIR into $CHIPYARD_DIR"

while IFS= read -r -d '' f; do
  rel=${f#$THESIS_DIR/generators/xiangshan/}
  install_file "$f" "$CHIPYARD_DIR/generators/xiangshan/$rel"
done < <(find "$THESIS_DIR/generators/xiangshan/src/main/scala" -type f -name '*.scala' -print0)

install_file "$THESIS_DIR/configs/XiangShanConfigs.scala" \
  "$CHIPYARD_DIR/generators/chipyard/src/main/scala/config/XiangShanConfigs.scala"

mkdir -p "$CHIPYARD_DIR/generators/xiangshan/src/main/resources/vsrc"

PATCH=$THESIS_DIR/patches/chipyard/xiangshan-build-sbt.patch
if grep -q 'lazy val xiangshan' "$CHIPYARD_DIR/build.sbt"; then
  echo "  build.sbt already defines 'lazy val xiangshan' (patch or equivalent manual edit present); not patching"
  grep -nE 'lazy val xiangshan|^\s*xiangshan,' "$CHIPYARD_DIR/build.sbt" | sed 's/^/    /'
elif git -C "$CHIPYARD_DIR" apply --check "$PATCH" 2>/dev/null; then
  git -C "$CHIPYARD_DIR" apply "$PATCH"
  echo "  build.sbt patched ($PATCH)"
else
  die "build.sbt patch does not apply cleanly; apply the two edits in $PATCH by hand"
fi

echo
echo "Done. Next: stage RTL with scripts/xs_stage_rtl.sh, then clear .classpath_cache/*.jar before make."
