#!/usr/bin/env python3
"""XiangShan probe logs -> CSV.  Usage: parse_xs.py [DIR ...]  (default: results/xs).
Later DIRs override earlier ones per benchmark (e.g. results/xs results/xs-long).
IPC = Spike instret (exact reference) / XiangShan mcycle. XiangShan's minstret lags
recent commits when read (see rt/calib: 1000 addi -> 974), so it is only checked
against Spike within TOL."""
import re, sys, pathlib
TOL = 64
B = pathlib.Path(__file__).resolve().parent.parent
ref = {m[1]: int(m[2]) for m in re.finditer(r'^(\S+)\s+ROI cycles=\d+ instret=(\d+)',
                                            (B/'spike-results.txt').read_text(), re.M)}
dirs = [pathlib.Path(p) for p in sys.argv[1:]] or [B/'results'/'xs']
print('bench,status,cycles,xs_instret,spike_instret,instret_diff,ipc,source')
for n in sorted(ref):
    row = f'{n},missing,,,{ref[n]},,,'
    for d in dirs:
        out, log = d/f'{n}.out', d/f'{n}.log'
        if not out.exists():
            continue
        L = log.read_text(errors='replace')
        ok = 'TestDriver.v:158' in L and 'FAILED' not in L
        w = [x[-8:] for x in re.findall(r'periph W .*?data=([0-9a-f]{16})', out.read_text(errors='replace'))]
        try:
            i = w.index('524f4931')
            cyc = int(w[i+2] + w[i+1], 16); ins = int(w[i+4] + w[i+3], 16); diff = ins - ref[n]
            status = ('pass' if abs(diff) <= TOL else 'pass-INSTRET-MISMATCH') if ok else 'FAIL'
            row = f'{n},{status},{cyc},{ins},{ref[n]},{diff},{ref[n]/cyc:.3f},{d.name}'
        except (ValueError, IndexError):
            row = f'{n},{"FAIL" if not ok else "pass-no-report"},,,{ref[n]},,,{d.name}'
    print(row)
