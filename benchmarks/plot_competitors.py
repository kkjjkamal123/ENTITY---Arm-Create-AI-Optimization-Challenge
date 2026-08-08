#!/usr/bin/env python3
"""Three-app comparison on identical hardware, model and workload.

    python3 benchmarks/plot_competitors.py

Writes competitor-comparison/three_app_comparison.png.

Every number here is read off the three apps' own benchmark screens. The current
session is 2026-07-20: all three apps re-measured on the same day, 5 runs each,
30-minute cooldown between apps, identical model file and PP512/TG128 workload.
Medians only - no app's best run is set against another's median.

The 2026-07-14 session is retained in SESSION_JULY below rather than overwritten,
because the two disagree in ways worth publishing: PocketPal and Arm swapped
places on decode, and ENTITY's prompt margin narrowed while its decode margin
widened. See competitor-comparison/README.md.

ENTITY's live-chat readout is deliberately NOT used: the other two figures are
synthetic PP512/TG128 benchmarks, and a chat measurement is not the same quantity.
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
# (label, colour, prompt tok/s, decode tok/s, decode sd if the app reports one)
APPS = [
    ("PocketPal AI\n6 threads", "#e34948", 88.32, 13.9, None),
    ("Arm AI Chat\n(Arm's own app)", "#eda100", 121.0, 12.4, 0.0751),
    ("ENTITY\n4 threads, pinned", "#2a78d6", 128.0, 18.2, None),
]

# Retained, not plotted: the 2026-07-14 session on the same phone and model file.
SESSION_JULY = [
    ("PocketPal AI", 86.4, 10.9),
    ("Arm AI Chat", 120.0, 12.9),
    ("ENTITY", 133.0, 15.6),
]


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

    # Decode. Error bars are drawn only where the app itself reports a spread.
    ax = axes[1]
    ax.set_facecolor(SURFACE)
    sds = [a[4] or 0.0 for a in APPS]
    drawn = ax.bar(labels, decode, 0.55, color=colours, zorder=3,
                   yerr=sds, capsize=6, ecolor=INK)
    top = max(decode)
    ax.set_ylim(0, top * 1.30)
    for rect, value in zip(drawn, decode):
        ax.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + top * 0.03,
                f"{value:.1f}", ha="center", va="bottom", fontsize=11, fontweight="bold", color=INK)
    ax.text(2, top * 1.16, "median of 5 runs", ha="center", fontsize=7.5, color=INK_MUTED)
    style(ax, "Token generation (tg 128)", "tokens / s")

    fig.suptitle("Same phone, same Llama-3.2-1B-Q4_0, same PP 512 / TG 128 workload, 2026-07-20",
                 fontsize=12.5, fontweight="bold", color=INK, x=0.05, ha="left", y=0.965)
    vs_arm_pp = (prompt[2] / prompt[1] - 1) * 100
    vs_arm_tg = (decode[2] / decode[1] - 1) * 100
    vs_pp_pp = (prompt[2] / prompt[0] - 1) * 100
    vs_pp_tg = (decode[2] / decode[0] - 1) * 100
    fig.text(0.05, 0.075,
             f"Against Arm's own reference app, on Arm's own silicon: {vs_arm_pp:+.0f}% prompt, "
             f"{vs_arm_tg:+.0f}% token generation. Against PocketPal: {vs_pp_pp:+.0f}% prompt, "
             f"{vs_pp_tg:+.0f}% token generation.",
             fontsize=9, color=INK)
    fig.text(0.05, 0.040,
             "Decode is where the thread-count policy acts, and where the margin is. Prompt is close because all "
             "three apps run Q4_0 and reach the same KleidiAI kernels.",
             fontsize=8, color=INK_MUTED)
    fig.text(0.05, 0.012,
             "All three are medians of 5 runs, one session, 30-minute cooldown between apps. Error bar shown "
             "where the app reports a spread.",
             fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.07, right=0.98, top=0.84, bottom=0.25, wspace=0.22)
    fig.savefig(OUT / "three_app_comparison.png", dpi=180, facecolor=SURFACE)
    plt.close(fig)
    print(f"wrote {OUT / 'three_app_comparison.png'}")


if __name__ == "__main__":
    main()
