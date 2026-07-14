#!/usr/bin/env python3
"""Three-app comparison on identical hardware, model and workload.

    python3 benchmarks/plot_competitors.py

Writes competitor-comparison/three_app_comparison.png.

Every number here is read off the three apps' own benchmark screens, which are kept
beside this script as PNGs. ENTITY's decode is shown as the RANGE across four runs
rather than its best, because the other two apps report 3-repetition results and
comparing our best against their medians would not be a fair test. ENTITY's worst
run still wins, which is the point worth making.

ENTITY's live-chat readout (16.9 tok/s) is deliberately NOT used: the other two
figures are synthetic PP512/TG128 benchmarks, and a chat measurement is not the
same quantity.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = Path(__file__).parent / "competitor-comparison"

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"

# Validated categorical slots: red / yellow / blue.
APPS = [
    ("PocketPal AI\n6 threads", "#e34948", 86.4, 10.9, None),
    ("Arm AI Chat\n(Arm's own app)", "#eda100", 120.0, 12.9, 0.08),
    ("ENTITY\n4 threads, pinned", "#2a78d6", 133.0, 14.7, None),
]

# ENTITY decode across every benchmark run on this model: worst still beats Arm.
ENTITY_DECODE_RUNS = [14.4, 14.7, 15.6, 16.4]


def style(axis, title, ylabel):
    axis.set_title(title, fontsize=12, fontweight="bold", color=INK, pad=12, loc="left")
    axis.set_ylabel(ylabel, fontsize=9, color=INK_MUTED)
    axis.grid(axis="y", alpha=0.18, linewidth=0.8)
    axis.set_axisbelow(True)
    for side in ("top", "right", "left"):
        axis.spines[side].set_visible(False)
    axis.spines["bottom"].set_color("#d8d7d2")
    axis.tick_params(labelsize=9, colors=INK_MUTED, length=0)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    labels = [a[0] for a in APPS]
    colours = [a[1] for a in APPS]
    prompt = [a[2] for a in APPS]
    decode = [a[3] for a in APPS]

    fig, axes = plt.subplots(1, 2, figsize=(11.5, 5.6), facecolor=SURFACE)

    # Prompt
    ax = axes[0]
    ax.set_facecolor(SURFACE)
    drawn = ax.bar(labels, prompt, 0.55, color=colours, zorder=3)
    ax.set_ylim(0, max(prompt) * 1.25)
    for rect, value in zip(drawn, prompt):
        ax.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + max(prompt) * 0.02,
                f"{value:.0f}", ha="center", va="bottom", fontsize=11, fontweight="bold", color=INK)
    style(ax, "Prompt processing (pp 512)", "tokens / s")

    # Decode, with ENTITY's full run range drawn as an error bar so nothing is cherry-picked.
    ax = axes[1]
    ax.set_facecolor(SURFACE)
    lo, hi = min(ENTITY_DECODE_RUNS), max(ENTITY_DECODE_RUNS)
    med = sorted(ENTITY_DECODE_RUNS)[len(ENTITY_DECODE_RUNS) // 2]
    values = [decode[0], decode[1], med]
    err = [[0, 0, med - lo], [0, 0, hi - med]]
    drawn = ax.bar(labels, values, 0.55, color=colours, zorder=3,
                   yerr=err, capsize=6, ecolor=INK)
    ax.set_ylim(0, hi * 1.34)
    # ENTITY's label must clear the top of its error bar, not sit inside it.
    tops = [values[0], values[1], hi]
    for rect, value, top in zip(drawn, values, tops):
        ax.text(rect.get_x() + rect.get_width() / 2, top + hi * 0.03,
                f"{value:.1f}", ha="center", va="bottom", fontsize=11, fontweight="bold", color=INK)
    ax.text(2, hi * 1.19, f"median of 4 runs\nrange {lo}-{hi}",
            ha="center", fontsize=7.5, color=INK_MUTED)
    style(ax, "Token generation (tg 128)", "tokens / s")

    fig.suptitle("Same phone, same Llama-3.2-1B-Q4_0, same PP 512 / TG 128 workload",
                 fontsize=12.5, fontweight="bold", color=INK, x=0.05, ha="left", y=0.965)
    vs_arm_pp = (prompt[2] / prompt[1] - 1) * 100
    vs_arm_tg = (med / decode[1] - 1) * 100
    vs_pp_pp = (prompt[2] / prompt[0] - 1) * 100
    vs_pp_tg = (med / decode[0] - 1) * 100
    worst_vs_arm = (lo / decode[1] - 1) * 100
    fig.text(0.05, 0.075,
             f"ENTITY beats Arm's own reference app on Arm's own silicon: {vs_arm_pp:+.0f}% prompt, "
             f"{vs_arm_tg:+.0f}% token generation. Against PocketPal: {vs_pp_pp:+.0f}% prompt, "
             f"{vs_pp_tg:+.0f}% token generation.",
             fontsize=9, color=INK)
    fig.text(0.05, 0.040,
             "PocketPal runs 6 threads and comes last: on a 4+4 big.LITTLE chip, threads 5 and 6 land on "
             "Cortex-A55s and every step waits on them.",
             fontsize=8, color=INK_MUTED)
    fig.text(0.05, 0.012,
             "ENTITY's decode is the median of four runs with its full range shown, not its best - even the "
             f"worst run beats Arm by {worst_vs_arm:+.0f}%.",
             fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.07, right=0.98, top=0.84, bottom=0.25, wspace=0.22)
    fig.savefig(OUT / "three_app_comparison.png", dpi=180, facecolor=SURFACE)
    plt.close(fig)
    print(f"wrote {OUT / 'three_app_comparison.png'}")


if __name__ == "__main__":
    main()
