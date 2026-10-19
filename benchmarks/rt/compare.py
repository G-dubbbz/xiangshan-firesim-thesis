#!/usr/bin/env python3
"""Combined table: cycles and IPC per benchmark on XiangShan (results/xs) and on each
Rocket/BOOM config (results/<CONFIG>).  Usage: compare.py CONFIG [CONFIG ...]
IPC = Spike instret / cycles. Every core's instret must equal Spike's exactly
(ROI_END fences before reading the counters); otherwise the cell is flagged."""
import re, sys, pathlib
B = pathlib.Path(__file__).resolve().parent.parent
R = B/'results'
ref = {m[1]: int(m[2]) for m in re.finditer(r'^(\S+)\s+ROI cycles=\d+ instret=(\d+)',
                                            (B/'spike-results.txt').read_text(), re.M)}
def xs(n):
    out, log = R/'xs'/f'{n}.out', R/'xs'/f'{n}.log'
    if not out.exists(): return None
    L = log.read_text(errors='replace')
    if 'TestDriver.v:158' not in L or 'FAILED' in L: return None
    w = [x[-8:] for x in re.findall(r'periph W .*?data=([0-9a-f]{16})', out.read_text(errors='replace'))]
    try:
        i = w.index('524f4931'); return int(w[i+2] + w[i+1], 16), int(w[i+4] + w[i+3], 16)
    except (ValueError, IndexError): return None
def htif(cfg, n):
    log = R/cfg/f'{n}.log'
    if not log.exists(): return None
    m = re.search(r'ROI cycles=(\d+) instret=(\d+)', log.read_text(errors='replace'))
    return (int(m[1]), int(m[2])) if m else None
cores = [('XiangShan', xs)] + [(c, lambda n, c=c: htif(c, n)) for c in sys.argv[1:]]
print('bench,spike_instret,' + ','.join(f'{c}_cycles,{c}_ipc' for c, _ in cores))
for n in sorted(ref):
    cols = []
    for c, f in cores:
        r = f(n)
        if r is None:            cols += ['FAIL', '']
        elif r[1] != ref[n]:     cols += [str(r[0]), f'INSTRET-{r[1]}']
        else:                    cols += [str(r[0]), f'{ref[n]/r[0]:.3f}']
    print(f'{n},{ref[n]},' + ','.join(cols))
