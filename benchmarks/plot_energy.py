#!/usr/bin/env python3
"""The energy graph: same work, measured battery cost.

    python3 benchmarks/plot_energy.py benchmarks/results/<unplugged-export>.csv

Writes plots/energy_per_task.png.

Every on-device LLM app reports tokens/second. Almost none report what the work
actually COST the battery. This figure integrates the measured power curve over each
benchmark pass to get joules, then divides by the tokens produced.

The finding it makes visible: all three configurations draw roughly the SAME power.
ENTITY wins on energy because it finishes sooner, not because it sips less current.
Energy is the area under the curve, so the left panel is a literal picture of the
right one.

Refuses to run on a charging export: the battery current would be the charger's.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"

ARMS = [
    ("naive", "Naive\n8 threads, all cores", "#e34948"),
    ("threads_only", "Threads only\n4 threads, no pinning", "#eda100"),
    ("optimized", "ENTITY Auto\n4 threads, pinned", "#2a78d6"),
]


def read(path: Path):
    samples = defaultdict(lambda: defaultdict(dict))
    meta = {}
    tokens = 128
    with path.open(newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            config, metric, index = row["config"], row["metric"], row["run_index"]
            if config == "meta":
                meta[metric] = row["value"]
                continue
            if not metric.startswith("sample_") or ":" not in index:
                continue
            try:
                value = float(row["value"])
            except ValueError:
                continue
            samples[(config, index.split(":", 1)[0])][index.split(":", 1)[1]][metric] = value
    if meta.get("tg", "").isdigit():
        tokens = int(meta["tg"])
    return samples, meta, tokens


def integrate(points):
    """Joules under the measured power curve, by trapezoid. Returns (seconds, joules)."""
    energy = 0.0
    for a, b in zip(points, points[1:]):
        dt = (b["sample_elapsed"] - a["sample_elapsed"]) / 1000.0
        energy += 0.5 * (a.get("sample_power", 0.0) + b.get("sample_power", 0.0)) * dt
    duration = (points[-1]["sample_elapsed"] - points[0]["sample_elapsed"]) / 1000.0
    return duration, energy


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("csv", type=Path)
    ap.add_argument("output_dir", type=Path, nargs="?", default=Path("benchmarks/plots"))
    args = ap.parse_args()

    samples, meta, tokens = read(args.csv)
    if (meta.get("charging") or "").lower() == "true":
        sys.exit("This export was taken while CHARGING: the battery current is the charger's, "
                 "not the workload's. Energy cannot be computed from it. Re-run unplugged.")

    # First pass of each arm, so the curve is one clean trace rather than an average of shapes.
    series = {}
    for key, _, _ in ARMS:
        passes = sorted(p for (c, p) in samples if c == key)
        if not passes:
            continue
        points = sorted(
            (p for p in samples[(key, passes[0])].values() if "sample_elapsed" in p),
            key=lambda p: p["sample_elapsed"],
        )
        if points:
            series[key] = points

    if len(series) < 2:
        sys.exit("need at least two arms with telemetry")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    fig, axes = plt.subplots(1, 2, figsize=(13, 5.8), facecolor=SURFACE,
                             gridspec_kw={"width_ratios": [1.55, 1]})

    # Left: the power curve. Energy is the shaded area, so the picture IS the number.
    ax = axes[0]
    ax.set_facecolor(SURFACE)
    stats = {}
    for key, label, colour in ARMS:
        if key not in series:
            continue
        points = series[key]
        xs = [p["sample_elapsed"] / 1000.0 for p in points]
        ys = [p.get("sample_power", 0.0) for p in points]
        duration, energy = integrate(points)
        stats[key] = (duration, energy)
        ax.plot(xs, ys, color=colour, linewidth=2, zorder=3, label=label.replace("\n", ", "))
        ax.fill_between(xs, ys, color=colour, alpha=0.13, zorder=2)
        ax.annotate(f"{energy:.0f} J", xy=(xs[-1], ys[-1]), xytext=(4, 2),
                    textcoords="offset points", fontsize=9, fontweight="bold", color=colour)

    ax.set(xlabel="Elapsed time in pass (s)", ylabel="Battery power (W)")
    ax.set_title("Power draw over one pass - energy is the shaded area",
                 fontsize=12, fontweight="bold", color=INK, pad=12, loc="left")
    ax.set_ylim(0, max(p.get("sample_power", 0) for pts in series.values() for p in pts) * 1.18)
    ax.grid(alpha=0.18, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.tick_params(labelsize=9, colors=INK_MUTED, length=0)
    ax.legend(frameon=False, fontsize=9, loc="lower right")

    # Right: the same thing as a number. Energy for identical work.
    ax = axes[1]
    ax.set_facecolor(SURFACE)
    keys = [k for k, _, _ in ARMS if k in stats]
    labels = [dict((k, l) for k, l, _ in ARMS)[k] for k in keys]
    colours = [dict((k, c) for k, _, c in ARMS)[k] for k in keys]
    joules = [stats[k][1] for k in keys]
    drawn = ax.bar(labels, joules, 0.55, color=colours, zorder=3)
    ax.set_ylim(0, max(joules) * 1.3)
    base = stats["naive"][1] if "naive" in stats else max(joules)
    for rect, key, value in zip(drawn, keys, joules):
        delta = "" if key == "naive" else f"\n{(value / base - 1) * 100:+.0f}%"
        ax.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + max(joules) * 0.02,
                f"{value:.0f} J{delta}", ha="center", va="bottom",
                fontsize=11, fontweight="bold", color=INK)
    ax.set_title(f"Battery energy to generate {tokens} tokens",
                 fontsize=12, fontweight="bold", color=INK, pad=12, loc="left")
    ax.set_ylabel("Joules (lower is better)", fontsize=9, color=INK_MUTED)
    ax.grid(axis="y", alpha=0.18, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.tick_params(labelsize=9, colors=INK_MUTED, length=0)

    saving = (1 - stats["optimized"][1] / base) * 100 if "optimized" in stats else 0
    fig.suptitle(f"Same 128 tokens, same phone: ENTITY uses {saving:.0f}% less battery",
                 fontsize=13.5, fontweight="bold", color=INK, x=0.045, ha="left", y=0.965)
    fig.text(0.045, 0.075,
             "All three configurations draw roughly the same watts. ENTITY wins on energy because it "
             "finishes sooner, not because it sips less current.",
             fontsize=9.5, color=INK)
    fig.text(0.045, 0.028,
             f"Integrated from {sum(len(p) for p in series.values())} battery-current samples in the "
             f"app's own CSV export. {meta.get('model', '')}, PP {meta.get('pp', '?')} / TG "
             f"{meta.get('tg', '?')}, unplugged.",
             fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.06, right=0.98, top=0.85, bottom=0.22, wspace=0.22)

    out = args.output_dir / "energy_per_task.png"
    fig.savefig(out, dpi=180, facecolor=SURFACE)
    plt.close(fig)
    print(f"wrote {out}")
    for key in keys:
        d, e = stats[key]
        print(f"  {key:13} {d:5.1f}s  {e:6.1f} J  {e / tokens:.2f} J/token")


if __name__ == "__main__":
    main()
