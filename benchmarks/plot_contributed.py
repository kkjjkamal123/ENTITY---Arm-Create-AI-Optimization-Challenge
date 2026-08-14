#!/usr/bin/env python3
"""Plot the contributed multi-device ablation.

    python3 benchmarks/plot_contributed.py

Writes contributed_multidevice.png into benchmarks/plots/.

Reads results/contributed_ablation_q4_0_20260723.csv, which is the committed export of
the Supabase dataset - so every number here is auditable against a file in the repo.

The chart answers one question the two development phones could not: does the
optimization generalise? It splits the ablation into its two independent steps, because
they behave completely differently and reporting only the combined figure hides that:

    step 1  naive -> threads_only   the thread count, affinity unchanged
    step 2  threads_only -> optimized   affinity, thread count unchanged

Deliberately excluded:
  * SM-S911B from step 1. Its naive arm is 6.72 +/- 5.95 tok/s (88.5% RSD) - noise, not a
    measurement, and a ratio built on it means nothing. Its step 2 arms are clean (0.5%
    and 0.6% RSD) so it appears there.
  * CPH2737 from the energy series. Those rows were produced by a build with the
    EXTRA_VOLTAGE unit bug and their watts are wrong by 1e6. Throughput is unaffected.
  * every Q8_0 row - a different model (Qwen2.5-0.5B), not comparable with Llama-3.2-1B.
"""

from __future__ import annotations

import csv
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

HERE = Path(__file__).parent
SRC = HERE / "results/contributed_ablation_q4_0_20260723.csv"
OUT = HERE / "plots"

# Validated categorical slots 1 and 2 (see the project's chart palette). Checked with the
# palette validator: adjacent-pair CVD dE 24.7 protan / 32.7 tritan, normal-vision 33.6.
from plot_theme import BLUE, GRID, INK, MUTED, ORANGE, SURFACE, suffixed

# Its naive arm is unusable (88.5% RSD); see the module docstring.
NO_STEP1 = {"Samsung SM-S911B"}


def load():
    rows = []
    with SRC.open() as f:
        for line in f:
            if line.startswith("#"):
                continue
            rows.append(line)
    data = defaultdict(dict)
    meta = {}
    for r in csv.DictReader(rows):
        dev = r["device"]
        meta[dev] = (r["soc"], r["power_valid"] == "true", int(r["runs_per_arm"]))
        data[dev][r["arm"]] = {
            "decode": float(r["decode_tok_s"]),
            "tok_per_w": float(r["tok_per_w"]) if r["tok_per_w"] else None,
        }
    return data, meta


def main():
    data, meta = load()
    OUT.mkdir(exist_ok=True)

    # Order by the step-1 gain so the panel reads as a ranking, not an arbitrary list.
    step1 = {}
    for dev, arms in data.items():
        if dev in NO_STEP1 or "naive" not in arms:
            continue
        step1[dev] = arms["threads_only"]["decode"] / arms["naive"]["decode"]
    order1 = sorted(step1, key=step1.get)

    step2, energy2 = {}, {}
    for dev, arms in data.items():
        if "threads_only" not in arms or "optimized" not in arms:
            continue
        t, o = arms["threads_only"], arms["optimized"]
        step2[dev] = (o["decode"] / t["decode"] - 1) * 100
        if meta[dev][1] and t["tok_per_w"] and o["tok_per_w"]:
            energy2[dev] = (o["tok_per_w"] / t["tok_per_w"] - 1) * 100
    order2 = sorted(step2, key=step2.get)

    fig, (ax1, ax2) = plt.subplots(
        1, 2, figsize=(13.5, 5.4), gridspec_kw={"width_ratios": [1, 1.25]}
    )
    fig.patch.set_facecolor(SURFACE)

    # ---- panel 1: thread count. one series, so no legend - the title names it. --------
    y = range(len(order1))
    vals = [step1[d] for d in order1]
    ax1.barh(list(y), vals, height=0.58, color=BLUE, zorder=3)
    for i, v in zip(y, vals):
        ax1.text(v + 0.06, i, f"{v:.2f}x", va="center", ha="left",
                 fontsize=11, color=INK, fontweight="bold")
    ax1.axvline(1.0, color=MUTED, lw=1.2, zorder=4)
    ax1.text(1.0, len(order1) - 0.35, " no change", fontsize=8.5, color=MUTED, va="top")
    ax1.set_yticks(list(y))
    ax1.set_yticklabels([f"{d}\n{meta[d][0]}" for d in order1], fontsize=9)
    ax1.set_xlim(0, max(vals) * 1.22)
    ax1.set_xlabel("decode speed-up", fontsize=9.5, color=MUTED)
    ax1.set_title("Step 1 — thread count\nPays on every device measured",
                  fontsize=12, fontweight="bold", color=INK, loc="left", pad=12)

    # ---- panel 2: affinity. diverging around zero, because the job is polarity. ------
    n = len(order2)
    h = 0.34
    for i, dev in enumerate(order2):
        ax2.barh(i + h / 2, step2[dev], height=h, color=BLUE, zorder=3)
        if dev in energy2:
            ax2.barh(i - h / 2, energy2[dev], height=h, color=ORANGE, zorder=3)
        else:
            ax2.text(0.4, i - h / 2, "power not measurable on this run",
                     fontsize=7.8, color=MUTED, va="center", ha="left", style="italic")
    for i, dev in enumerate(order2):
        v = step2[dev]
        ax2.text(v + (0.7 if v >= 0 else -0.7), i + h / 2, f"{v:+.1f}%",
                 va="center", ha="left" if v >= 0 else "right",
                 fontsize=9.5, color=INK, fontweight="bold")
        if dev in energy2:
            e = energy2[dev]
            ax2.text(e + (0.7 if e >= 0 else -0.7), i - h / 2, f"{e:+.1f}%",
                     va="center", ha="left" if e >= 0 else "right",
                     fontsize=9.5, color=INK)
    ax2.axvline(0, color=INK, lw=1.4, zorder=5)
    ax2.set_yticks(range(n))
    ax2.set_yticklabels([f"{d}\n{meta[d][0]}" for d in order2], fontsize=9)
    lo = min(list(step2.values()) + list(energy2.values()))
    hi = max(list(step2.values()) + list(energy2.values()))
    pad = (hi - lo) * 0.22
    ax2.set_xlim(lo - pad, hi + pad)
    ax2.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:+.0f}%"))
    ax2.set_xlabel("change from turning pinning ON", fontsize=9.5, color=MUTED)
    ax2.set_title("Step 2 — core pinning\nDevice-dependent on speed, negative on energy",
                  fontsize=12, fontweight="bold", color=INK, loc="left", pad=12)

    handles = [
        plt.Rectangle((0, 0), 1, 1, color=BLUE),
        plt.Rectangle((0, 0), 1, 1, color=ORANGE),
    ]
    ax2.legend(handles, ["decode tok/s", "energy  tok/W"], loc="lower right",
               frameon=False, fontsize=9.5)

    for ax in (ax1, ax2):
        ax.xaxis.grid(True, color=GRID, lw=0.9, zorder=0)
        ax.set_axisbelow(True)
        for side in ("top", "right", "left"):
            ax.spines[side].set_visible(False)
        ax.spines["bottom"].set_color(GRID)
        ax.tick_params(axis="both", length=0, colors=MUTED, labelsize=9)
        for lbl in ax.get_yticklabels():
            lbl.set_color(INK)

    fig.suptitle(
        "ENTITY on five SoCs the author does not own — Llama-3.2-1B-Q4_0, unplugged",
        fontsize=14.5, fontweight="bold", color=INK, x=0.012, ha="left", y=0.985,
    )
    fig.text(
        0.012, 0.022,
        "Thread count is the lever that generalises. Pinning is a speed lever with a power cost: "
        "median +0.6% decode but -1.5% tok/W, positive on only 3 of 6 rows.\n"
        "Pixel 10 is the clean case — +29.3% faster for +33.5% more power, so tokens per watt falls. "
        "Source: results/contributed_ablation_q4_0_20260723.csv.\n"
        "SM-S911B omitted from step 1 (naive arm 88.5% RSD — noise). CPH2737 has no energy bar "
        "(power measured by a build with the EXTRA_VOLTAGE unit bug).",
        fontsize=8.2, color=MUTED, ha="left", va="bottom", linespacing=1.6,
    )

    fig.tight_layout(rect=(0, 0.115, 1, 0.945))
    dst = OUT / suffixed("contributed_multidevice.png")
    fig.savefig(dst, dpi=200, facecolor=SURFACE)
    print(f"wrote {dst}")


if __name__ == "__main__":
    main()
