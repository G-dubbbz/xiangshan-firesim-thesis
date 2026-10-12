#!/usr/bin/env python3
"""
xs_prefix_modules.py -- prefix every XiangShan Verilog module name (except the top)
so XiangShan's pre-generated RTL cannot collide with Chipyard's firtool output.

Why: Chipyard firtool output and blackbox resources are written into the same
gen-collateral/ directory, one file per module. XiangShan's HuanCun L3 and
Chipyard's InclusiveCache share module names (SourceA, SourceD, SinkC, MSHR, ...),
so same-named files overwrite each other and the wrong module gets compiled
(Verilator %Error-PINNOTFOUND in InclusiveCacheBankScheduler.sv, 2026-10-09).

What it does (all in place, after backing up):
  1. Finds every `module NAME` defined in *.v / *.sv under --vsrc.
  2. Renames every such module except --top to PREFIX+NAME, at:
       - definitions:     ^\\s*module NAME
       - instantiations:  ^\\s*NAME [#(]...  or  ^\\s*NAME inst_name (
  3. Renames every .v/.sv file except the top's file to PREFIX+filename.
  4. Rewrites addResource("/vsrc/<file>") lines in --scala to the new names.
  5. Verifies: no unprefixed instantiation of a renamed module remains, and
     every prefixed module is defined exactly once.

Usage:
  python3 xs_prefix_modules.py --vsrc <dir> --scala <XSTopBlackBox.scala> \\
      --backup-dir <dir outside resources/> [--prefix XS_] [--top XSTop] [--dry-run]
"""
import argparse
import os
import re
import shutil
import sys
import time

VEXT = (".v", ".sv")
DEF_RE = re.compile(r"^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)", re.M)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vsrc", required=True)
    ap.add_argument("--scala", required=True)
    ap.add_argument("--backup-dir", required=True)
    ap.add_argument("--prefix", default="XS_")
    ap.add_argument("--top", default="XSTop")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    vsrc = os.path.abspath(a.vsrc)
    files = sorted(f for f in os.listdir(vsrc) if f.endswith(VEXT))
    if not files:
        sys.exit(f"no .v/.sv files in {vsrc}")

    # Refuse to run twice.
    already = [f for f in files if f.startswith(a.prefix)]
    if already:
        sys.exit(f"{len(already)} files already start with {a.prefix!r} (e.g. {already[0]}); "
                 f"refusing to prefix twice. Restore from backup first.")

    texts = {}
    defined = {}
    for f in files:
        with open(os.path.join(vsrc, f), encoding="utf-8", errors="surrogateescape") as fh:
            t = fh.read()
        texts[f] = t
        for m in DEF_RE.findall(t):
            defined.setdefault(m, []).append(f)

    dup = {m: fs for m, fs in defined.items() if len(fs) > 1}
    if dup:
        print(f"WARNING: {len(dup)} module(s) defined in more than one file before renaming "
              f"(e.g. {next(iter(dup.items()))}); continuing, they stay duplicated.")

    if a.top not in defined:
        sys.exit(f"top module {a.top!r} not found in {vsrc}")

    names = sorted((m for m in defined if m != a.top), key=len, reverse=True)
    print(f"{len(files)} files, {len(defined)} modules defined, renaming {len(names)} (top {a.top} kept)")

    alt = "|".join(re.escape(n) for n in names)
    def_sub = re.compile(r"^(\s*module\s+)(" + alt + r")\b", re.M)
    # Instantiation: NAME at line start, followed by #( params or an instance identifier then (
    inst_sub = re.compile(
        r"^(\s*)(" + alt + r")(?=\s*#\s*\(|\s+(?:\\\S+|[A-Za-z_][A-Za-z0-9_$]*)\s*(?:\[[^\]]*\]\s*)?\()",
        re.M)

    new_texts = {}
    n_def = n_inst = 0
    for f, t in texts.items():
        t, c1 = def_sub.subn(lambda m: m.group(1) + a.prefix + m.group(2), t)
        t, c2 = inst_sub.subn(lambda m: m.group(1) + a.prefix + m.group(2), t)
        n_def += c1
        n_inst += c2
        new_texts[f] = t
    print(f"rewrote {n_def} definitions, {n_inst} instantiations")

    # Verify: no remaining unprefixed instantiation of a renamed module.
    leftover = []
    for f, t in new_texts.items():
        for m in inst_sub.finditer(t):
            leftover.append((f, m.group(2)))
    if leftover:
        sys.exit(f"VERIFY FAILED: {len(leftover)} unprefixed instantiations remain, e.g. {leftover[:5]}")

    # Verify: every prefixed module defined exactly once (modulo pre-existing duplicates).
    redef = {}
    for f, t in new_texts.items():
        for m in DEF_RE.findall(t):
            redef.setdefault(m, 0)
            redef[m] += 1
    bad = [m for m in names if redef.get(a.prefix + m, 0) != len(defined[m])]
    if bad:
        sys.exit(f"VERIFY FAILED: definition count changed for {bad[:5]}")
    if a.top not in redef:
        sys.exit("VERIFY FAILED: top module lost")

    top_files = set(defined[a.top])
    rename = {f: (f if f in top_files else a.prefix + f) for f in files}

    with open(a.scala, encoding="utf-8") as fh:
        scala = fh.read()
    res_re = re.compile(r'addResource\("/vsrc/([^"]+)"\)')
    scala_refs = res_re.findall(scala)
    missing = [r for r in scala_refs if r not in rename and r.endswith(VEXT)]
    new_scala, n_res = res_re.subn(
        lambda m: f'addResource("/vsrc/{rename.get(m.group(1), m.group(1))}")', scala)
    print(f"scala: {len(scala_refs)} addResource lines, {n_res} rewritten"
          + (f", {len(missing)} reference files not in vsrc (left as is): {missing[:5]}" if missing else ""))
    not_in_scala = [f for f in files if f not in set(scala_refs)]
    if not_in_scala:
        print(f"note: {len(not_in_scala)} vsrc files have no addResource line, e.g. {not_in_scala[:5]}")

    if a.dry_run:
        print("dry run: nothing written")
        return

    stamp = time.strftime("%Y%m%d-%H%M%S")
    bdir = os.path.join(os.path.abspath(a.backup_dir), f"xs-prefix-backup-{stamp}")
    os.makedirs(bdir)
    shutil.copytree(vsrc, os.path.join(bdir, "vsrc"))
    shutil.copy2(a.scala, os.path.join(bdir, os.path.basename(a.scala)))
    print(f"backup: {bdir}")

    for f in files:
        src = os.path.join(vsrc, f)
        dst = os.path.join(vsrc, rename[f])
        with open(dst, "w", encoding="utf-8", errors="surrogateescape") as fh:
            fh.write(new_texts[f])
        if dst != src:
            os.remove(src)
    with open(a.scala, "w", encoding="utf-8") as fh:
        fh.write(new_scala)
    print("done")


if __name__ == "__main__":
    main()
