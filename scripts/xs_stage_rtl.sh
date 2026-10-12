#!/usr/bin/env bash
# xs_stage_rtl.sh -- stage freshly generated XiangShan RTL into the Chipyard
# XiangShan generator, prefix all module names, and sync addResource lines.
#
# Steps:
#   1. Check that XS_RTL holds a clean `make verilog` output (XSTop.sv present,
#      no SimTop.sv from a sim-verilog/emu run).
#   2. Write a manifest (XiangShan commit, dirty flag, sha256 of every source file).
#   3. Replace every .v/.sv file in the generator's resources/vsrc with XS_RTL's.
#   4. Prefix every module except XSTop with XS_ (scripts/xs_prefix_modules.py).
#   5. Rewrite the addResource list in XSTopBlackBox.scala (scripts/xs_sync_resources.py).
#
# Environment (defaults match the IDUN Apptainer layout):
#   CHIPYARD_DIR  Chipyard checkout              (default /work/chipyard)
#   XS_HOME       XiangShan checkout             (default $NOOP_HOME or /work/xs-env/XiangShan)
#   XS_RTL        directory with generated RTL   (default $XS_HOME/build/rtl)
#   BACKUP_DIR    where the prefix script backs up vsrc (default /work/xs-prefix-backups)
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CHIPYARD_DIR=${CHIPYARD_DIR:-/work/chipyard}
XS_HOME=${XS_HOME:-${NOOP_HOME:-/work/xs-env/XiangShan}}
XS_RTL=${XS_RTL:-$XS_HOME/build/rtl}
BACKUP_DIR=${BACKUP_DIR:-/work/xs-prefix-backups}

GEN=$CHIPYARD_DIR/generators/xiangshan
VS=$GEN/src/main/resources/vsrc
SC=$GEN/src/main/scala/xiangshan/XSTopBlackBox.scala

die() { echo "ERROR: $*" >&2; exit 1; }

[ -f "$XS_RTL/XSTop.sv" ] || die "$XS_RTL/XSTop.sv not found (run 'make verilog' in XiangShan first)"
[ ! -e "$XS_RTL/SimTop.sv" ] || die "$XS_RTL contains SimTop.sv: output mixed with a sim-verilog/emu run. Run 'rm -rf build && make verilog CONFIG=...' first."
[ -f "$SC" ] || die "$SC not found (run scripts/xs_install_into_chipyard.sh first)"

mapfile -t SRC < <(find "$XS_RTL" -maxdepth 1 -type f \( -name '*.sv' -o -name '*.v' \) -printf '%f\n' | sort)
[ "${#SRC[@]}" -gt 0 ] || die "no .v/.sv files in $XS_RTL"
echo "Found ${#SRC[@]} RTL files in $XS_RTL"

# Manifest: provenance of the staged RTL (written next to src/, not packed into the jar).
MAN=$GEN/xs-rtl-manifest.txt
{
  echo "# XiangShan RTL staged into Chipyard on $(date -Is)"
  echo "xs_rtl_dir: $XS_RTL"
  if git -C "$XS_HOME" rev-parse HEAD >/dev/null 2>&1; then
    echo "xiangshan_commit: $(git -C "$XS_HOME" rev-parse HEAD)"
    echo "xiangshan_branch: $(git -C "$XS_HOME" rev-parse --abbrev-ref HEAD)"
    if [ -n "$(git -C "$XS_HOME" status --porcelain --untracked-files=no)" ]; then
      echo "xiangshan_dirty: yes (see xiangshan-local.diff)"
      git -C "$XS_HOME" diff > "$GEN/xiangshan-local.diff"
    else
      echo "xiangshan_dirty: no"
      rm -f "$GEN/xiangshan-local.diff"
    fi
    echo "xiangshan_submodules:"
    git -C "$XS_HOME" submodule status | sed 's/^/  /'
  else
    echo "xiangshan_commit: UNKNOWN ($XS_HOME is not a git checkout)"
  fi
  echo "file_count: ${#SRC[@]}"
  echo "sha256 (unprefixed sources):"
  (cd "$XS_RTL" && sha256sum "${SRC[@]}") | sed 's/^/  /'
} > "$MAN"
echo "Wrote manifest $MAN"

mkdir -p "$VS"
find "$VS" -maxdepth 1 -type f \( -name '*.sv' -o -name '*.v' \) -delete
(cd "$XS_RTL" && cp "${SRC[@]}" "$VS/")
echo "Copied ${#SRC[@]} files into $VS"

# Prefix module names. The prefix script also rewrites addResource lines in the
# Scala file it is given; pass a throwaway file and sync the real one afterwards,
# so this works no matter what the Scala file currently lists.
TMP_SCALA=$(mktemp)
mkdir -p "$BACKUP_DIR"
python3 "$SCRIPT_DIR/xs_prefix_modules.py" --vsrc "$VS" --scala "$TMP_SCALA" --backup-dir "$BACKUP_DIR"
rm -f "$TMP_SCALA"

python3 "$SCRIPT_DIR/xs_sync_resources.py" --vsrc "$VS" --scala "$SC"

n_unprefixed=$(find "$VS" -maxdepth 1 -type f \( -name '*.sv' -o -name '*.v' \) ! -name 'XS_*' | wc -l)
[ "$n_unprefixed" -eq 1 ] || die "expected exactly 1 unprefixed file (XSTop.sv), found $n_unprefixed"
[ -f "$VS/XSTop.sv" ] || die "XSTop.sv missing after prefixing"

echo
echo "Staging done. Next (inside the container):"
echo "  rm -f $CHIPYARD_DIR/.classpath_cache/*.jar"
echo "  rm -rf $CHIPYARD_DIR/sims/verilator/generated-src/chipyard.harness.TestHarness.XiangShanConfig"
