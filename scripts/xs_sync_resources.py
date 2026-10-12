#!/usr/bin/env python3
"""
xs_sync_resources.py -- make the addResource(...) list in XSTopBlackBox.scala
match exactly the Verilog files present in the staged vsrc directory.

Every existing line of the form
    addResource("/vsrc/<name>")
is removed, and one line per .v/.sv file in --vsrc (sorted) is inserted where
the first such line used to be, with the same indentation. Nothing else in the
Scala file is touched.

Run after (re)staging and prefixing XiangShan RTL (see scripts/xs_stage_rtl.sh).

Usage:
  python3 xs_sync_resources.py --vsrc <vsrc dir> --scala <XSTopBlackBox.scala> [--dry-run]
"""
import argparse
import os
import re
import sys

VEXT = (".v", ".sv")
LINE_RE = re.compile(r'^(\s*)addResource\("/vsrc/[^"]+"\)\s*$')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vsrc", required=True)
    ap.add_argument("--scala", required=True)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    files = sorted(f for f in os.listdir(a.vsrc) if f.endswith(VEXT))
    if not files:
        sys.exit(f"no .v/.sv files in {a.vsrc}")

    with open(a.scala, encoding="utf-8") as fh:
        lines = fh.read().split("\n")

    idx = [i for i, l in enumerate(lines) if LINE_RE.match(l)]
    if not idx:
        sys.exit(f'no addResource("/vsrc/...") line found in {a.scala}; '
                 'add one placeholder line inside class XSTop where the list should go')
    indent = LINE_RE.match(lines[idx[0]]).group(1)
    old = {lines[i].strip() for i in idx}
    new_block = [f'{indent}addResource("/vsrc/{f}")' for f in files]
    new = {l.strip() for l in new_block}

    out = []
    for i, l in enumerate(lines):
        if i == idx[0]:
            out.extend(new_block)
        elif i in set(idx):
            continue
        else:
            out.append(l)

    print(f"vsrc files: {len(files)}; addResource lines before: {len(idx)}, after: {len(new_block)}; "
          f"added {len(new - old)}, removed {len(old - new)}")
    if a.dry_run:
        print("dry run: nothing written")
        return
    with open(a.scala, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))
    print(f"updated {a.scala}")


if __name__ == "__main__":
    main()
