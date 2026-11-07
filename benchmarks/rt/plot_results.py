#!/usr/bin/env python3
"""Plot microbenchmark results from results/comparison.csv (written by rt/compare.py).

Figures (PDF for the thesis, PNG for quick viewing), written to results/plots/:
  ipc.pdf                IPC per benchmark and core, grouped bars, split into
                         control-flow / compute / memory panels.
  normalized_cycles.pdf  ROI cycles of each core relative to a baseline core
                         (default: Medium BOOM), log scale, with geometric mean.
  relative_ipc.pdf       IPC of each core relative to the baseline, as bars from 1x
                         (up = higher IPC), same panels as ipc.pdf plus the geomean.
  summary.txt            geometric means and per-benchmark ratios as plain text.

Usage:
  python3 rt/plot_results.py [--csv results/comparison.csv] [--out results/plots]
                             [--baseline MediumBoomV3Config] [--caveat-memory TEXT]

Cores are detected from the CSV's '<core>_cycles' columns, so extra configs
(e.g. XiangShanNoL2) appear automatically once compare.py includes them.
Cells marked FAIL or INSTRET-... are left out of the plots and listed in summary.txt.
"""
import argparse, csv, math, pathlib, sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.ticker
from matplotlib.lines import Line2D

# Benchmark categories, as in Øverby & Aksum (2026), Table 5.2.1.
CATEGORIES = [
    ('Control flow', ['CCa', 'CCe', 'CCh', 'CCh_st', 'CCl', 'CCm', 'CF1', 'CRf', 'CS1', 'CS3']),
    ('Compute',      ['DP1f', 'DPcvt', 'ED1', 'EF', 'EI', 'EM1', 'EM5']),
    ('Memory',       ['MC', 'MCS', 'MI', 'MIP', 'ML2_BW_ldst', 'STL2b']),
]

# Display names; unknown configs are shown with their raw name.
PRETTY = {
    'XiangShan': 'XiangShan Minimal',
    'XiangShanNoL2Config': 'XiangShan Minimal (no Chipyard L2)',
    'MediumBoomV3Config': 'Medium BOOM',
    'MegaBoomV3Config': 'Mega BOOM',
    'LargeBoomV3Config': 'Large BOOM',
}

# Colour follows the core, never its position. Validated categorical slots
# (blue, orange, aqua pass all-pairs CVD checks; marker shape is a second cue).
COLOR = {
    'XiangShan': '#2a78d6',
    'MediumBoomV3Config': '#eb6834',
    'MegaBoomV3Config': '#1baf7a',
    'XiangShanNoL2Config': '#6250d6',
    'LargeBoomV3Config': '#eda100',
}
FALLBACK_COLORS = ['#e87ba4', '#008300', '#e34948']
MARKER = {'XiangShan': 'o', 'MediumBoomV3Config': 's', 'MegaBoomV3Config': 'D',
          'XiangShanNoL2Config': '^', 'LargeBoomV3Config': 'v'}

INK, INK2, GRID, SURFACE = '#0b0b0b', '#52514e', '#e4e3df', '#ffffff'

plt.rcParams.update({
    'font.size': 9, 'axes.titlesize': 9, 'axes.labelsize': 9,
    'xtick.labelsize': 8, 'ytick.labelsize': 8, 'legend.fontsize': 8,
    'axes.edgecolor': INK2, 'axes.labelcolor': INK, 'text.color': INK,
    'xtick.color': INK2, 'ytick.color': INK2,
    'axes.spines.top': False, 'axes.spines.right': False,
    'pdf.fonttype': 42, 'ps.fonttype': 42,   # embed TrueType: selectable text in the PDF
    'savefig.bbox': 'tight', 'savefig.dpi': 200,
})


def load(path):
    rows = list(csv.DictReader(open(path)))
    cores = [c[:-len('_cycles')] for c in rows[0] if c.endswith('_cycles')]
    data, problems = {}, []
    for r in rows:
        b = r['bench']
        data[b] = {}
        for c in cores:
            cyc, ipc = r[f'{c}_cycles'], r[f'{c}_ipc']
            try:
                data[b][c] = (int(cyc), float(ipc))
            except ValueError:
                problems.append(f'{b} on {c}: cycles={cyc!r} ipc={ipc!r} (left out)')
    return cores, data, problems


def style(core, i):
    color = COLOR.get(core, FALLBACK_COLORS[i % len(FALLBACK_COLORS)])
    return color, MARKER.get(core, 'o'), PRETTY.get(core, core)


def ordered_benches(data):
    known = [b for _, bs in CATEGORIES for b in bs]
    groups = [(name, [b for b in bs if b in data]) for name, bs in CATEGORIES]
    extra = [b for b in sorted(data) if b not in known]
    if extra:
        groups.append(('Other', extra))
    return [(n, bs) for n, bs in groups if bs]


def geomean(xs):
    xs = [x for x in xs if x > 0]
    return math.exp(sum(map(math.log, xs)) / len(xs)) if xs else float('nan')


def plot_ipc(cores, data, groups, out, caveat):
    widths = [len(bs) for _, bs in groups]
    fig, axes = plt.subplots(1, len(groups), figsize=(7.2, 3.0), sharey=True,
                             gridspec_kw={'width_ratios': widths, 'wspace': 0.08})
    axes = [axes] if len(groups) == 1 else axes
    ymax = max(v[1] for b in data for v in data[b].values()) * 1.12
    n = len(cores)
    bw = 0.8 / n
    for ax, (name, bs) in zip(axes, groups):
        for i, c in enumerate(cores):
            color, _, label = style(c, i)
            xs = [j + (i - (n - 1) / 2) * bw for j in range(len(bs))]
            ys = [data[b][c][1] if c in data[b] else 0 for b in bs]
            ax.bar(xs, ys, width=bw, color=color, edgecolor=SURFACE, linewidth=0.8,
                   label=label, zorder=2)
        ax.set_xticks(range(len(bs)))
        ax.set_xticklabels([b.replace('_', '​_') for b in bs], rotation=60, ha='right',
                           rotation_mode='anchor')
        ax.set_xlim(-0.6, len(bs) - 0.4)
        ax.set_ylim(0, ymax)
        ax.set_title(name, color=INK, pad=4)
        ax.grid(axis='y', color=GRID, linewidth=0.6, zorder=0)
        ax.tick_params(axis='x', length=0)
        if name == 'Memory' and caveat:
            ax.set_title(name + '*', color=INK, pad=4)
    axes[0].set_ylabel('IPC (instructions per cycle)')
    for ax in axes[1:]:
        ax.spines['left'].set_visible(False)
        ax.tick_params(axis='y', length=0)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='upper center', ncol=n, frameon=False,
               bbox_to_anchor=(0.5, 1.06))
    if caveat:
        fig.text(0.07, -0.12, '* ' + caveat, fontsize=7, color=INK2, style='italic', ha='left', va='top')
    save(fig, out / 'ipc')


def plot_normalized(cores, data, groups, base, out, caveat):
    others = [c for c in cores if c != base]
    rows = []  # (label, {core: ratio}) top to bottom
    for name, bs in groups:
        rows.append((name, None))
        for b in bs:
            if base in data[b]:
                rows.append((b, {c: data[b][c][0] / data[b][base][0] for c in others if c in data[b]}))
    means = {c: geomean([r[c] for _, r in rows if r and c in r]) for c in others}
    rows.append(('Geometric mean', means))

    fig, ax = plt.subplots(figsize=(5.2, 0.19 * len(rows) + 0.9))
    ys = list(range(len(rows)))[::-1]
    allr = [v for _, r in rows if r for v in r.values()]
    lo, hi = min(allr + [1]) / 1.25, max(allr + [1]) * 1.25
    ax.set_xscale('log')
    ax.set_xlim(lo, hi)
    ax.axvline(1, color=INK2, linewidth=1, zorder=1)
    for y, (label, r) in zip(ys, rows):
        if r is None:   # category header
            ax.text(lo, y, label + ('*' if label == 'Memory' and caveat else ''), ha='left', va='center',
                fontsize=8, fontweight='bold', color=INK)
            continue
        ax.axhline(y, color=GRID, linewidth=0.5, zorder=0)
        if label == 'Geometric mean':
            ax.axhspan(y - 0.5, y + 0.5, color='#f2f1ed', zorder=0)
        for i, c in enumerate(others):
            if c in r:
                color, marker, _ = style(c, cores.index(c))
                dy = (i - (len(others) - 1) / 2) * 0.28   # dodge so equal values stay visible
                ax.plot(r[c], y - dy, marker=marker, markersize=6, color=color, linestyle='none',
                        markeredgecolor=SURFACE, markeredgewidth=0.8, zorder=3)
    ax.set_yticks([y for y, (_, r) in zip(ys, rows) if r is not None])
    ax.set_yticklabels([lab for lab, r in rows if r is not None])
    ax.tick_params(axis='y', length=0)
    ax.spines['left'].set_visible(False)
    ticks = [t for t in (0.125, 0.25, 0.5, 1, 2, 4, 8) if lo <= t <= hi]
    ax.set_xticks(ticks)
    ax.set_xticklabels([f'{t:g}×' for t in ticks])
    ax.minorticks_off()
    ax.set_xlabel(f'ROI cycles relative to {PRETTY.get(base, base)} (log scale; < 1× = fewer cycles)')
    ax.set_ylim(-0.7, len(rows) - 0.3)
    handles = [Line2D([], [], marker=style(c, cores.index(c))[1], color=style(c, cores.index(c))[0],
                      linestyle='none', markersize=6, label=PRETTY.get(c, c)) for c in others]
    handles.append(Line2D([], [], color=INK2, linewidth=1, label=f'{PRETTY.get(base, base)} = 1×'))
    ax.legend(handles=handles, loc='lower center', bbox_to_anchor=(0.5, 1.0), ncol=len(handles),
              frameon=False, handletextpad=0.3, columnspacing=1.2)
    if caveat:
        fig.text(0.02, 0.0, '* ' + caveat, fontsize=7, color=INK2, style='italic', ha='left', va='top')
    save(fig, out / 'normalized_cycles')
    return means


def plot_relative_ipc(cores, data, groups, base, out, caveat):
    """IPC of each core divided by the baseline's IPC, per benchmark, as bars that start
    at 1x (the baseline) on a log axis: up = higher IPC than the baseline, down = lower.
    Same instruction count on every core, so this equals baseline cycles / core cycles."""
    others = [c for c in cores if c != base]
    rel = {b: {c: data[b][c][1] / data[b][base][1] for c in others if c in data[b]}
           for _, bs in groups for b in bs if base in data[b]}
    means = {c: geomean([rel[b][c] for b in rel if c in rel[b]]) for c in others}
    panels = [(n, [b for b in bs if b in rel]) for n, bs in groups] + [('Mean', ['Geomean'])]
    rel['Geomean'] = means

    vals = [v for b in rel for v in rel[b].values()]
    lo, hi = min(vals + [1]) / 1.2, max(vals + [1]) * 1.2
    widths = [len(bs) for _, bs in panels[:-1]] + [2.6]
    fig, axes = plt.subplots(1, len(panels), figsize=(7.6, 3.0), sharey=True,
                             gridspec_kw={'width_ratios': widths, 'wspace': 0.08})
    n = len(others)
    bw = 0.8 / n
    for ax, (name, bs) in zip(axes, panels):
        ax.set_yscale('log')
        ax.set_ylim(lo, hi)
        ax.axhline(1, color=INK2, linewidth=1, zorder=3)
        for i, c in enumerate(others):
            color, _, label = style(c, cores.index(c))
            xs = [j + (i - (n - 1) / 2) * bw for j in range(len(bs))]
            ys = [rel[b].get(c, 1) for b in bs]
            # bar from 1x to the value (up or down)
            ax.bar(xs, [y - 1 for y in ys], bottom=1, width=bw, color=color, edgecolor=SURFACE,
                   linewidth=0.8, label=label, zorder=2)
            # values within 2% of the baseline give a bar too thin to see: mark them with a short line
            near = [(x, y) for x, y in zip(xs, ys) if abs(math.log(y)) < math.log(1.02)]
            if near:
                ax.hlines([y for _, y in near], [x - bw * 0.4 for x, _ in near],
                          [x + bw * 0.4 for x, _ in near], color=color, linewidth=2.2, zorder=4)
        ax.set_xticks(range(len(bs)))
        ax.set_xticklabels([b.replace('_', '\u200b_') for b in bs], rotation=60, ha='right',
                           rotation_mode='anchor')
        ax.set_xlim(-0.6, len(bs) - 0.4) if name != 'Mean' else ax.set_xlim(-0.8, 0.8)
        title = name + ('*' if name == 'Memory' and caveat else '')
        ax.set_title(title, color=INK, pad=4)
        ax.grid(axis='y', color=GRID, linewidth=0.6, zorder=0)
        ax.tick_params(axis='x', length=0)
        if name == 'Mean':
            ax.set_facecolor('#f6f5f2')
            ax.set_xticklabels(['Geometric\nmean'], rotation=0, ha='center')
            for i, c in enumerate(others):   # label the summary bars with their value
                x = (i - (n - 1) / 2) * bw
                v = means[c]
                ax.text(x, v * (1.05 if v >= 1 else 1 / 1.05), f'{v:.2f}\u00d7', ha='center',
                        va='bottom' if v >= 1 else 'top', fontsize=7, color=INK, rotation=90)
    ticks = [t for t in (0.125, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8) if lo <= t <= hi]
    axes[0].set_yticks(ticks)
    axes[0].set_yticklabels([f'{t:g}\u00d7' for t in ticks])
    axes[0].yaxis.set_minor_locator(matplotlib.ticker.NullLocator())
    axes[0].set_ylabel(f'IPC relative to {PRETTY.get(base, base)} (log)')
    for ax in axes[1:]:
        ax.spines['left'].set_visible(False)
        ax.tick_params(axis='y', length=0)
    handles, labels = axes[0].get_legend_handles_labels()
    handles.append(Line2D([], [], color=INK2, linewidth=1))
    labels.append(f'{PRETTY.get(base, base)} = 1\u00d7')
    fig.legend(handles, labels, loc='upper center', ncol=len(handles), frameon=False,
               bbox_to_anchor=(0.5, 1.06))
    if caveat:
        fig.text(0.07, -0.12, '* ' + caveat, fontsize=7, color=INK2, style='italic', ha='left', va='top')
    save(fig, out / 'relative_ipc')


def save(fig, stem):
    for ext in ('pdf', 'png'):
        fig.savefig(f'{stem}.{ext}', facecolor=SURFACE)
    plt.close(fig)
    print(f'wrote {stem}.pdf / .png')


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--csv', default=root / 'results' / 'comparison.csv')
    ap.add_argument('--out', default=root / 'results' / 'plots')
    ap.add_argument('--baseline', default='MediumBoomV3Config')
    ap.add_argument('--caveat-memory', default='',
                    help='note printed on the memory panel, e.g. "XiangShan: extra Chipyard L2"')
    a = ap.parse_args()
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)

    cores, data, problems = load(a.csv)
    if a.baseline not in cores:
        sys.exit(f'baseline {a.baseline!r} not in CSV; cores are {cores}')
    groups = ordered_benches(data)

    plot_ipc(cores, data, groups, out, a.caveat_memory)
    means = plot_normalized(cores, data, groups, a.baseline, out, a.caveat_memory)
    plot_relative_ipc(cores, data, groups, a.baseline, out, a.caveat_memory)

    with open(out / 'summary.txt', 'w') as f:
        f.write(f'source: {a.csv}\nbaseline: {a.baseline}\n\n')
        f.write('Geometric mean of ROI cycles relative to baseline:\n')
        for c, m in means.items():
            f.write(f'  {PRETTY.get(c, c):40s} {m:.3f}\n')
        f.write('\nPer benchmark (cycles / baseline cycles):\n')
        others = [c for c in cores if c != a.baseline]
        f.write('  ' + 'bench'.ljust(13) + ''.join(PRETTY.get(c, c)[:20].rjust(22) for c in others) + '\n')
        for _, bs in groups:
            for b in bs:
                if a.baseline not in data[b]:
                    continue
                f.write('  ' + b.ljust(13) + ''.join(
                    (f'{data[b][c][0] / data[b][a.baseline][0]:.3f}' if c in data[b] else '-').rjust(22)
                    for c in others) + '\n')
        if problems:
            f.write('\nLeft out:\n' + ''.join(f'  {p}\n' for p in problems))
    print(f'wrote {out / "summary.txt"}')
    if problems:
        print('WARNING: some cells were left out, see summary.txt', file=sys.stderr)


if __name__ == '__main__':
    main()
