#!/usr/bin/env python3
"""Plot the four-arm ENTITY Bench v1.1.0 device exports.

    python3 benchmarks/plot_four_arm.py

Writes four_arm_decode_20260718.png into benchmarks/plots/.

Unlike plot_results.py (which reads the aggregated device-result-template.csv and
knows only the three-arm naive/threads-only/Auto shape), this script reads the raw
per-run ENTITY Bench exports directly, because they carry a fourth arm the template
schema does not model: `efficiency` (four threads pinned to the LITTLE cluster).

Only the app's own `median` and `stddev` rows are plotted. Per-run rows are read to
draw the point cloud so a reader can see the spread the median sits in; nothing is
interpolated or back-filled.
"""

from __future__ import annotations

import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent
OUT = HERE / "plots"

EXPORTS = [
    ("CMF Phone 1\nDimensity 7300", HERE / "results/entity_1b-q4_0_unplugged_5run_cmf_20260718.csv"),
    ("OPPO CPH2729\nSnapdragon 6 Gen 4", HERE / "results/entity_1b-q4_0_unplugged_5run_oppo_20260718.csv"),
]

# Same three slots plot_results.py validated, plus one green slot for the new arm.
ARMS = [
    ("naive", "Naive  (8 thr, all cores)", "#e34948"),
    ("threads_only", "Threads only  (4 thr, no pin)", "#eda100"),
    ("optimized", "ENTITY Auto  (4 thr, perf-pinned)", "#2a78d6"),
    ("efficiency", "Efficiency  (4 thr, LITTLE-pinned)", "#2f9e6f"),
]

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"


def load(path):
    """Return {config: {'median':{m:v}, 'stddev':{m:v}, 'runs':{m:[v..]}}}."""
    out = {}
    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.reader(handle):
            if len(row) < 4 or row[0] in ("meta", "config"):
                continue
            cfg, ri, metric, val = row[0], row[1], row[2], row[3]
            if metric.startswith("sample_"):
                continue
            try:
                v = float(val)
            except ValueError:
                continue
            slot = out.setdefault(cfg, {"median": {}, "stddev": {}, "runs": {}})
            if ri == "median":
                slot["median"][metric] = v
            elif ri == "stddev":
                slot["stddev"][metric] = v
            elif ri.isdigit():
                slot["runs"].setdefault(metric, []).append(v)
    return out


def style(axis, title, ylabel):
    axis.set_title(title, fontsize=12, fontweight="bold", color=INK, pad=12, loc="left")
    axis.set_ylabel(ylabel, fontsize=9, color=INK_MUTED)
    axis.grid(axis="y", alpha=0.18, linewidth=0.8)
    axis.set_axisbelow(True)
    for side in ("top", "right", "left"):
        axis.spines[side].set_visible(False)
    axis.spines["bottom"].set_color("#d8d7d2")
    axis.tick_params(labelsize=9, colors=INK_MUTED, length=0)


def panel(axis, data, metric, ylabel, title, fmt="{:.1f}"):
    groups = [g for g, _ in EXPORTS]
    n = len(ARMS)
    width = 0.80 / n
    xs = range(len(groups))
    medians = [[data[g][cfg]["median"].get(metric) for g, _ in EXPORTS]
               for cfg, _, _ in ARMS]
    peak = max(v for col in medians for v in col if v is not None)

    for i, (cfg, name, colour) in enumerate(ARMS):
        offs = [x + (i - (n - 1) / 2) * width for x in xs]
        vals = medians[i]
        axis.bar(offs, [v or 0 for v in vals], width * 0.9,
                 label=name, color=colour, zorder=3)
        for j, (g, _) in enumerate(EXPORTS):
            # point cloud: every retained run, so the median's spread is visible
            for rv in data[g][cfg]["runs"].get(metric, []):
                axis.scatter(offs[j], rv, s=9, color=INK, alpha=0.35, zorder=4,
                             linewidths=0)
        for rect_x, value in zip(offs, vals):
            if value is None:
                continue
            axis.text(rect_x, value + peak * 0.02, fmt.format(value), ha="center",
                      va="bottom", rotation=90, fontsize=8, fontweight="bold", color=INK)

    style(axis, title, ylabel)
    axis.set_ylim(0, peak * 1.30)
    axis.set_xticks(list(xs))
    axis.set_xticklabels(groups, fontsize=9, color=INK_MUTED)


def main():
    data = {g: load(path) for g, path in EXPORTS}
    OUT.mkdir(exist_ok=True)

    fig, (left, right) = plt.subplots(1, 2, figsize=(13, 6), facecolor=SURFACE)
    for ax in (left, right):
        ax.set_facecolor(SURFACE)

    panel(left, data, "tg", "Decode throughput (tokens / s)",
          "Decode throughput by arm", fmt="{:.1f}")
    panel(right, data, "tok_per_w", "Energy efficiency (tokens / W)",
          "Energy efficiency by arm", fmt="{:.2f}")

    left.legend(frameon=False, fontsize=8.5, ncol=1, loc="upper left")
    fig.suptitle(
        "ENTITY Bench v1.1.0  -  Llama-3.2-1B Q4_0, PP 512 / TG 128, unplugged, 5 runs/arm  (2026-07-18)",
        fontsize=11, fontweight="bold", color=INK, x=0.02, ha="left")
    fig.text(0.02, 0.005,
             "Bars are the app's median; dots are the individual runs. "
             "The efficiency arm (LITTLE-cluster pinning) is new in v1.1.0.",
             fontsize=8, color=INK_MUTED, ha="left")
    fig.tight_layout(rect=(0, 0.02, 1, 0.95))
    fig.savefig(OUT / "four_arm_decode_20260718.png", dpi=150, facecolor=SURFACE)
    print("wrote", OUT / "four_arm_decode_20260718.png")


if __name__ == "__main__":
    main()
