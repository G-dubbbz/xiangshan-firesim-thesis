#!/usr/bin/env python3
"""Plot microbenchmark results from results/comparison.csv (written by rt/compare.py).

Figures (PDF for the thesis, PNG for quick viewing), written to results/plots/:
  normalized_ipc.pdf  IPC of every core normalized to the baseline core (default:
                      Medium BOOM = 1 for every benchmark), as bars from 0, one panel per
                      category, each panel ending with that category's mean.
  ipc.pdf             absolute IPC per benchmark and core, same panels, no means.
  summary.txt         per-category means (harmonic and arithmetic) and the normalized
                      IPC of every benchmark, as plain text.

Usage:
  python3 rt/plot_results.py [--csv results/comparison.csv] [--out results/plots]
                             [--baseline MediumBoomV3Config]
                             [--order MediumBoomV3Config,XiangShan,MegaBoomV3Config]
                             [--mean harmonic|arithmetic] [--ymax 3] [--caveat-memory TEXT]

Core order: bars, legend and tables run left to right in --order (default Medium BOOM,
XiangShan, Mega BOOM); cores in the CSV that are not listed follow in CSV order.
Colours follow the core, never its position.

Normalized IPC: every core executes exactly the same instructions (compare.py checks
instret against Spike), so IPC_core / IPC_baseline = cycles_baseline / cycles_core.
It is computed from the cycle counts, which are exact (the CSV's IPC is rounded).

Means: one per category, over the benchmarks in that panel. The default is the harmonic
mean, the right mean for normalized rates: it equals 1 / (arithmetic mean of the
normalized cycle counts), i.e. the speedup over a run in which every benchmark takes
the same time on the baseline, and a single large speedup cannot dominate it.
--mean arithmetic gives the plain average of the normalized IPC values instead.

Categories follow the suite's naming (and Øverby & Aksum's Table 5.2.1):
C* = control flow; D* (data parallel) and E* (execution) = compute; M* and ST* = memory.

--ymax: bars above this value are cut at the top with a break mark and their value
printed above them, so differences around 1 stay readable. --ymax 0 = no cut.

Cells marked FAIL or INSTRET-... in the CSV are left out of the plots and listed in
summary.txt. A benchmark without a baseline result is left out of the normalized plot.
"""
import argparse, csv, math, pathlib, sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.gridspec import GridSpec
from matplotlib.patches import Patch

DEFAULT_ORDER = ['MediumBoomV3Config', 'XiangShan', 'MegaBoomV3Config']

# Category by benchmark-name prefix (first match wins).
CATEGORY_RULES = [('ST', 'Memory'), ('C', 'Control flow'), ('D', 'Compute'),
                  ('E', 'Compute'), ('M', 'Memory')]
CATEGORY_ORDER = ['Control flow', 'Compute', 'Memory', 'Other']

# Display names; unknown configs are shown with their raw name.
PRETTY = {
    'XiangShan': 'XiangShan Minimal',
    'XiangShanNoL2Config': 'XiangShan Minimal (no Chipyard L2)',
    'MediumBoomV3Config': 'Medium BOOM',
    'MegaBoomV3Config': 'Mega BOOM',
    'LargeBoomV3Config': 'Large BOOM',
}

# Colour follows the core, never its position. Validated categorical slots (orange, blue,
# aqua pass the adjacent-pair CVD checks in this order; bar order is a second cue).
COLOR = {
    'MediumBoomV3Config': '#eb6834',
    'XiangShan': '#2a78d6',
    'MegaBoomV3Config': '#1baf7a',
    'XiangShanNoL2Config': '#6250d6',
    'LargeBoomV3Config': '#eda100',
}
FALLBACK_COLORS = ['#e87ba4', '#008300', '#e34948']

INK, INK2, GRID, SURFACE, MEANBG = '#0b0b0b', '#52514e', '#e4e3df', '#ffffff', '#f2f1ed'
MEAN_W = 2.0                 # width of the mean group, in bar-group units (wider: room for labels)
MEAN_GAP = 0.5               # space between the last benchmark and the mean group
MEAN_EXTRA = MEAN_GAP + MEAN_W   # extra horizontal units a panel needs for its mean group
LABEL_SP = 0.6               # minimum spacing of rotated value labels, in bar-group units

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


def order_cores(cores, order):
    first = [c for c in order if c in cores]
    return first + [c for c in cores if c not in first]


def style(core, cores):
    i = cores.index(core)
    return COLOR.get(core, FALLBACK_COLORS[i % len(FALLBACK_COLORS)]), PRETTY.get(core, core)


def category(bench):
    for prefix, name in CATEGORY_RULES:
        if bench.startswith(prefix):
            return name
    return 'Other'


def grouped(benches):
    groups = {}
    for b in sorted(benches):
        groups.setdefault(category(b), []).append(b)
    return [(n, groups[n]) for n in CATEGORY_ORDER if n in groups]


def mean(xs, kind):
    xs = [x for x in xs if x > 0]
    if not xs:
        return float('nan')
    return len(xs) / sum(1 / x for x in xs) if kind == 'harmonic' else sum(xs) / len(xs)


def pack_rows(groups, extra, row_groups):
    """Split category panels into figure rows of at most row_groups bar groups."""
    rows, cur, width = [], [], 0
    for name, bs in groups:
        w = len(bs) + extra
        if cur and width + w > row_groups:
            rows.append(cur); cur, width = [], 0
        cur.append((name, bs)); width += w
    if cur:
        rows.append(cur)
    return rows


def make_axes(rows, extra, row_height):
    """One axis per panel, all rows on a common horizontal scale (one unit per bar group)."""
    units = max(sum(len(bs) + extra for _, bs in row) for row in rows)
    h = row_height * len(rows)
    fig = plt.figure(figsize=(7.2, h))
    outer = GridSpec(len(rows), 1, figure=fig, hspace=0.75, top=1 - 0.3 / h)
    axes = []
    for r, row in enumerate(rows):
        widths = [len(bs) + extra for _, bs in row]
        filler = units - sum(widths)
        inner = outer[r].subgridspec(1, len(row) + (1 if filler else 0), wspace=0.06,
                                     width_ratios=widths + ([filler] if filler else []))
        row_axes = [fig.add_subplot(inner[0, i]) for i in range(len(row))]
        for ax in row_axes[1:]:
            ax.sharey(row_axes[0])
        axes.append(row_axes)
    return fig, axes


def setup_panel(ax, title, labels, mean_label, first_in_row):
    n = len(labels)
    xm = n - 0.5 + MEAN_GAP + MEAN_W / 2
    ticks = list(range(n)) + ([xm] if mean_label else [])
    ax.set_xticks(ticks)
    ax.set_xticklabels([b.replace('_', '​_') for b in labels] + ([mean_label] if mean_label else []),
                       rotation=60, ha='right', rotation_mode='anchor')
    if mean_label:
        lab = ax.get_xticklabels()[-1]   # horizontal, two lines: fits the wide mean group
        lab.set_rotation(0); lab.set_ha('center'); lab.set_rotation_mode('default')
        lab.set_fontweight('bold'); lab.set_color(INK); lab.set_fontsize(7)
        ax.axvspan(n - 0.5 + MEAN_GAP, n - 0.5 + MEAN_GAP + MEAN_W, color=MEANBG, zorder=0, linewidth=0)
    ax.set_xlim(-0.6, (n - 0.5 + MEAN_GAP + MEAN_W) if mean_label else (n - 0.4))
    ax.set_title(title, color=INK, pad=4)
    ax.grid(axis='y', color=GRID, linewidth=0.6, zorder=0)
    ax.tick_params(axis='x', length=0)
    if not first_in_row:
        ax.spines['left'].set_visible(False)
        ax.tick_params(axis='y', length=0, labelleft=False)


def bars(ax, x0, values, cores, plot_cores, bw, cap):
    """Grouped bars at x0 (one per core in order); values cut at cap get a break mark + label."""
    n = len(plot_cores)
    cut = []
    for i, c in enumerate(plot_cores):
        v = values.get(c)
        x = x0 + (i - (n - 1) / 2) * bw
        if v is None or not math.isfinite(v):   # failed / missing result: say so, don't draw 0
            ax.text(x, 0.03 * (cap or ax.get_ylim()[1] or 1), 'n/a', ha='center', va='bottom',
                    rotation=90, fontsize=6, color=INK2, zorder=4)
            continue
        h = min(v, cap) if cap else v
        ax.bar(x, h, width=bw, color=style(c, cores)[0], edgecolor=SURFACE, linewidth=0.8, zorder=2)
        if cap and v > cap:   # break mark: two surface-coloured slashes near the top
            for dy in (0.06, 0.12):
                y = cap * (1 - dy)
                ax.plot([x - bw * 0.45, x + bw * 0.45], [y - cap * 0.015, y + cap * 0.015],
                        color=SURFACE, linewidth=1.6, zorder=3, solid_capstyle='butt')
            cut.append((x, v))
    if cut:   # value labels above the cut bars, spread apart when neighbours are cut too
        cx = sum(x for x, _ in cut) / len(cut)
        sp = max(bw, LABEL_SP)
        for k, (x, v) in enumerate(cut):
            lx = x if len(cut) == 1 else cx + (k - (len(cut) - 1) / 2) * sp
            ax.text(lx, cap * 1.01, f'{v:.1f}', ha='center', va='bottom', rotation=90,
                    fontsize=6.5, color=INK, zorder=4, clip_on=False)


def legend(fig, cores, plot_cores, extra_label=None):
    handles = [Patch(color=style(c, cores)[0], label=style(c, cores)[1]) for c in plot_cores]
    if extra_label:
        handles[0].set_label(extra_label)
    fig.legend(handles=handles, loc='lower center', ncol=len(handles), frameon=False,
               bbox_to_anchor=(0.5, 0.985), handlelength=1.2, columnspacing=1.6)


def caveat_text(fig, caveat):
    """Footnote placed below the lowest x tick label of the figure."""
    if not caveat:
        return
    fig.canvas.draw()
    r = fig.canvas.get_renderer()
    low = min(t.get_window_extent(r).y0 for ax in fig.axes for t in ax.get_xticklabels() if t.get_text())
    y = fig.transFigure.inverted().transform((0, low))[1]
    fig.text(0.07, y - 0.02, '* ' + caveat, fontsize=7, color=INK2, style='italic', ha='left', va='top')


def plot_normalized(cores, data, base, kind, cap, row_groups, out, caveat):
    norm = {b: {c: data[b][base][0] / data[b][c][0] for c in cores if c in data[b]}
            for b in data if base in data[b]}
    groups = grouped(norm)
    rows = pack_rows(groups, MEAN_EXTRA, row_groups)
    fig, axes = make_axes(rows, MEAN_EXTRA, 2.15)
    n = len(cores)
    bw = 0.8 / n
    means = {}
    top = cap if cap else max(v for b in norm for v in norm[b].values()) * 1.08
    mean_label = 'Harmonic\nmean' if kind == 'harmonic' else 'Arithmetic\nmean'
    mbw = MEAN_W * 0.8 / n
    for row, row_axes in zip(rows, axes):
        for k, (ax, (name, bs)) in enumerate(zip(row_axes, row)):
            for j, b in enumerate(bs):
                bars(ax, j, norm[b], cores, cores, bw, cap)
            m = {c: mean([norm[b][c] for b in bs if c in norm[b]], kind) for c in cores}
            means[name] = m
            xm = len(bs) - 0.5 + MEAN_GAP + MEAN_W / 2
            bars(ax, xm, m, cores, cores, mbw, cap)
            for i, c in enumerate(cores):   # value labels on the summary bars (not the baseline)
                if c == base or not math.isfinite(m[c]) or (cap and m[c] > cap):
                    continue
                ax.text(xm + (i - (n - 1) / 2) * mbw, m[c] + top * 0.02, f'{m[c]:.2f}', ha='center',
                        va='bottom', rotation=90, fontsize=6.5, color=INK, zorder=4)
            ax.axhline(1, color=INK2, linewidth=0.9, zorder=3)
            title = name + ('*' if name == 'Memory' and caveat else '')
            setup_panel(ax, title, bs, mean_label, k == 0)
            ax.set_ylim(0, top * (1.14 if cap else 1.0))
        row_axes[0].set_ylabel(f'IPC normalized to\n{PRETTY.get(base, base)}')
        if cap:
            step = 0.5 if cap <= 4 else 1.0
            row_axes[0].set_yticks([t * step for t in range(int(cap / step + 1e-9) + 1)])
    legend(fig, cores, cores, f'{PRETTY.get(base, base)} (= 1)')
    caveat_text(fig, caveat)
    save(fig, out / 'normalized_ipc')
    return norm, groups, means


def plot_ipc(cores, data, row_groups, out, caveat):
    groups = grouped(data)
    rows = pack_rows(groups, 0, row_groups)
    fig, axes = make_axes(rows, 0, 2.15)
    n = len(cores)
    bw = 0.8 / n
    top = max(v[1] for b in data for v in data[b].values()) * 1.1
    for row, row_axes in zip(rows, axes):
        for k, (ax, (name, bs)) in enumerate(zip(row_axes, row)):
            for j, b in enumerate(bs):
                bars(ax, j, {c: data[b][c][1] for c in data[b]}, cores, cores, bw, None)
            title = name + ('*' if name == 'Memory' and caveat else '')
            setup_panel(ax, title, bs, None, k == 0)
            ax.set_ylim(0, top)
        row_axes[0].set_ylabel('IPC (instructions\nper cycle)')
    legend(fig, cores, cores)
    caveat_text(fig, caveat)
    save(fig, out / 'ipc')


def save(fig, stem):
    for ext in ('pdf', 'png'):
        fig.savefig(f'{stem}.{ext}', facecolor=SURFACE)
    plt.close(fig)
    print(f'wrote {stem}.pdf / .png')


def write_summary(path, src, cores, base, kind, norm, groups, problems, data):
    others = [c for c in cores if c != base]
    w = 22
    with open(path, 'w') as f:
        f.write(f'source: {src}\nbaseline: {PRETTY.get(base, base)} (= 1)\n'
                f'plotted mean: {kind}\n\n')
        f.write('Mean normalized IPC per category (harmonic | arithmetic):\n')
        f.write('  ' + 'category'.ljust(18) + ''.join(PRETTY.get(c, c)[:w - 2].rjust(w) for c in others) + '\n')
        for name, bs in groups:
            f.write('  ' + f'{name} ({len(bs)})'.ljust(18) + ''.join(
                (f'{mean([norm[b][c] for b in bs if c in norm[b]], "harmonic"):.3f} | '
                 f'{mean([norm[b][c] for b in bs if c in norm[b]], "arithmetic"):.3f}').rjust(w)
                for c in others) + '\n')
        f.write('\nNormalized IPC per benchmark (baseline cycles / core cycles):\n')
        f.write('  ' + 'bench'.ljust(14) + ''.join(PRETTY.get(c, c)[:w - 2].rjust(w) for c in cores) + '\n')
        for name, bs in groups:
            f.write(f'  [{name}]\n')
            for b in bs:
                f.write('  ' + b.ljust(14) + ''.join(
                    (f'{norm[b][c]:.3f}' if c in norm[b] else '-').rjust(w) for c in cores) + '\n')
        no_base = sorted(b for b in data if base not in data[b])
        if problems or no_base:
            f.write('\nLeft out:\n' + ''.join(f'  {p}\n' for p in problems))
            f.write(''.join(f'  {b}: no {PRETTY.get(base, base)} result, not normalized\n' for b in no_base))
    print(f'wrote {path}')


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--csv', default=root / 'results' / 'comparison.csv')
    ap.add_argument('--out', default=root / 'results' / 'plots')
    ap.add_argument('--baseline', default='MediumBoomV3Config')
    ap.add_argument('--order', default=','.join(DEFAULT_ORDER),
                    help='core order, left to right (comma-separated CSV core names)')
    ap.add_argument('--mean', choices=['harmonic', 'arithmetic'], default='harmonic')
    ap.add_argument('--ymax', type=float, default=3.0,
                    help='cut normalized bars above this value (0 = no cut)')
    ap.add_argument('--row-groups', type=int, default=32,
                    help='max bar groups per figure row before categories wrap to a new row')
    ap.add_argument('--caveat-memory', default='',
                    help='note printed on the memory panel, e.g. "XiangShan: extra Chipyard L2"')
    a = ap.parse_args()
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)

    cores, data, problems = load(a.csv)
    cores = order_cores(cores, a.order.split(','))
    if a.baseline not in cores:
        sys.exit(f'baseline {a.baseline!r} not in CSV; cores are {cores}')

    norm, groups, _ = plot_normalized(cores, data, a.baseline, a.mean, a.ymax or None,
                                      a.row_groups, out, a.caveat_memory)
    plot_ipc(cores, data, a.row_groups, out, a.caveat_memory)
    write_summary(out / 'summary.txt', a.csv, cores, a.baseline, a.mean, norm, groups, problems, data)
    if problems:
        print('WARNING: some cells were left out, see summary.txt', file=sys.stderr)


if __name__ == '__main__':
    main()
