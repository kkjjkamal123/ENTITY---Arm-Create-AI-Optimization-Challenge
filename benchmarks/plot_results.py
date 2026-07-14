#!/usr/bin/env python3
"""Plot the published device results from device-result-template.csv.

    python3 benchmarks/plot_results.py

Writes decode_attribution.png, kleidiai_prompt_ttft.png and energy_efficiency.png
into benchmarks/plots/.

This script plots ONLY the measured medians recorded in the CSV. Rows whose arms
were never run carry `not-measured` and are skipped rather than interpolated, and
power columns marked `not-valid-charging` are skipped rather than back-filled from
another run. Per-sample telemetry (frequency/temperature/watts over time) is NOT
plotted here - that lives in an app CSV export and is handled by plot_telemetry.py.
"""

from __future__ import annotations

import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent
CSV = HERE / "device-result-template.csv"
OUT = HERE / "plots"

# Categorical slots 6 / 3 / 1 from the validated palette. Order is semantic: the
# out-of-the-box default, the honest baseline, the shipped path.
ARMS = [
    ("naive", "Naive\n8 threads, all cores", "#e34948"),
    ("threads_only", "Threads only\n4 threads, no pinning", "#eda100"),
    ("auto", "ENTITY Auto\n4 threads, pinned", "#2a78d6"),
]

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_MUTED = "#52514e"


def num(value):
    """A measured float, or None when the CSV says the number does not exist."""
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def load():
    with CSV.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def label_for(row):
    size = "1B" if "1B" in row["model_gguf"] else "3B"
    quant = row["quantization"]
    kleidi = "KleidiAI" if row["kleidiai_accelerated"] == "true" else "no KleidiAI"
    tail = ", charging" if row["charging"] == "true" else ""
    return f"{size} {quant}\n{kleidi}{tail}"


def style(axis, title, ylabel):
    axis.set_title(title, fontsize=12, fontweight="bold", color=INK, pad=12, loc="left")
    axis.set_ylabel(ylabel, fontsize=9, color=INK_MUTED)
    axis.grid(axis="y", alpha=0.18, linewidth=0.8)
    axis.set_axisbelow(True)
    for side in ("top", "right", "left"):
        axis.spines[side].set_visible(False)
    axis.spines["bottom"].set_color("#d8d7d2")
    axis.tick_params(labelsize=9, colors=INK_MUTED, length=0)


def bars(axis, groups, series, fmt="{:.1f}"):
    """groups: [group label]; series: [(arm label, colour, [value per group])]."""
    n = len(series)
    width = 0.76 / n
    xs = range(len(groups))
    peak = max(v for _, _, values in series for v in values if v is not None)
    for i, (name, colour, values) in enumerate(series):
        offs = [x + (i - (n - 1) / 2) * width for x in xs]
        # width*0.9 leaves the surface gap between adjacent bars.
        drawn = axis.bar(offs, [v or 0 for v in values], width * 0.9,
                         label=name, color=colour, zorder=3)
        # Direct labels on every bar: the palette's yellow slot is below 3:1 on this
        # surface, so the relief rule requires visible values, not colour alone.
        # Rotated upright so neighbouring bars of near-equal height cannot collide -
        # which is exactly the case the ablation produces (threads-only vs Auto).
        for rect, value in zip(drawn, values):
            if value is None:
                continue
            axis.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + peak * 0.015,
                      fmt.format(value), ha="center", va="bottom", rotation=90,
                      fontsize=8, fontweight="bold", color=INK)
    axis.set_ylim(0, peak * 1.28)   # headroom for the rotated labels and the legend
    axis.set_xticks(list(xs))
    axis.set_xticklabels(groups, fontsize=9, color=INK_MUTED)


def decode_attribution(rows):
    """The chart the whole ablation exists to produce."""
    use = [r for r in rows if num(r["threads_only_decode_tok_s"]) is not None]
    groups = [label_for(r) for r in use]
    series = [
        (name, colour, [num(r[f"{key}_decode_tok_s"]) for r in use])
        for key, name, colour in ARMS
    ]
    fig, axis = plt.subplots(figsize=(11, 6), facecolor=SURFACE)
    axis.set_facecolor(SURFACE)
    bars(axis, groups, series)
    style(axis, "Where the decode speed-up actually comes from",
          "Decode throughput (tokens / s)")
    axis.legend(frameon=False, fontsize=9, ncol=3, loc="upper right")

    notes = []
    for r in use:
        naive, thr, auto = (num(r["naive_decode_tok_s"]),
                            num(r["threads_only_decode_tok_s"]),
                            num(r["auto_decode_tok_s"]))
        notes.append(f"{label_for(r).splitlines()[0]}: threads {(thr/naive - 1)*100:+.0f}%, "
                     f"pinning {(auto/thr - 1)*100:+.0f}%")
    fig.text(0.055, 0.105,
             "Dropping 8 threads to 4 earns the entire gain. Pinning those threads to the "
             "performance cores adds essentially nothing.",
             fontsize=9, color=INK)
    # Two lines: one long line overflows the figure at this font size.
    half = (len(notes) + 1) // 2
    fig.text(0.055, 0.055, "   |   ".join(notes[:half]), fontsize=8, color=INK_MUTED)
    fig.text(0.055, 0.020, "   |   ".join(notes[half:]), fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.075, right=0.98, top=0.90, bottom=0.24)
    fig.savefig(OUT / "decode_attribution.png", dpi=180, facecolor=SURFACE)
    plt.close(fig)
    return "decode_attribution.png"


def kleidiai(rows):
    """Q3_K_L vs Q4_0 on the same phone: the quantization gates Arm's kernels."""
    q3 = next((r for r in rows if r["quantization"] == "Q3_K_L"
               and r["app_version"].startswith("v2.1")), None)
    q4 = next((r for r in rows if r["quantization"] == "Q4_0" and "1B" in r["model_gguf"]), None)
    if not (q3 and q4):
        return None

    # Use the THREADS-ONLY arm, not Auto. The Q3_K_L run predates the prompt-pool fix, so its
    # Auto arm still widened prompt processing to all 8 cores while the Q4_0 run's Auto did not.
    # Comparing the two Auto arms would bundle that fix into the KleidiAI number. Threads-only is
    # 4 threads, no pinning, no widening in BOTH builds - identical config, so the only thing that
    # differs is the quantization, which is exactly the claim being made.
    labels = ["Q3_K_L\n(KleidiAI cannot run)", "Q4_0\n(KleidiAI runs)"]
    colours = ["#e34948", "#2a78d6"]
    prompt = [num(q3["threads_only_prompt_tok_s"]), num(q4["threads_only_prompt_tok_s"])]
    ttft = [num(q3["threads_only_ttft_ms"]) / 1000, num(q4["threads_only_ttft_ms"]) / 1000]

    # Two measures of different scale get two panels. Never a dual axis.
    fig, axes = plt.subplots(1, 2, figsize=(11, 5.6), facecolor=SURFACE)
    for axis, values, title, ylabel, fmt in (
        (axes[0], prompt, "Prompt processing (higher is better)", "Prompt throughput (tokens / s)", "{:.0f}"),
        (axes[1], ttft, "Time to first token (lower is better)", "TTFT (seconds)", "{:.1f}s"),
    ):
        axis.set_facecolor(SURFACE)
        drawn = axis.bar(labels, values, 0.5, color=colours, zorder=3)
        axis.set_ylim(0, max(values) * 1.22)
        for rect, value in zip(drawn, values):
            axis.text(rect.get_x() + rect.get_width() / 2, rect.get_height() + max(values) * 0.02,
                      fmt.format(value), ha="center", va="bottom",
                      fontsize=11, fontweight="bold", color=INK)
        style(axis, title, ylabel)

    fig.suptitle("KleidiAI ships kernels for Q4_0 and Q8_0 only - every other quant falls back to generic ggml",
                 fontsize=12.5, fontweight="bold", color=INK, x=0.055, ha="left", y=0.965)
    fig.text(0.055, 0.075,
             "Same phone, same 512-token prompt, same 4-thread unpinned config - only the quantization differs.",
             fontsize=9, color=INK)
    fig.text(0.055, 0.028,
             "Prompt eval is a compute-bound GEMM, which is what KleidiAI accelerates. Decode is "
             "memory-bandwidth-bound and does not improve: it tracks bytes-per-weight, not kernel quality.",
             fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.075, right=0.98, top=0.84, bottom=0.22, wspace=0.25)
    fig.savefig(OUT / "kleidiai_prompt_ttft.png", dpi=180, facecolor=SURFACE)
    plt.close(fig)
    return "kleidiai_prompt_ttft.png"


def energy(rows):
    """tok/W, unplugged rows only - the app hides power while charging and so do we."""
    use = [r for r in rows
           if num(r["threads_only_efficiency_tok_w"]) is not None
           and r["charging"] == "false"]
    if not use:
        return None
    groups = [label_for(r) for r in use]
    series = [
        (name, colour, [num(r[f"{key}_efficiency_tok_w"]) for r in use])
        for key, name, colour in ARMS
    ]
    fig, axis = plt.subplots(figsize=(9.5, 5.6), facecolor=SURFACE)
    axis.set_facecolor(SURFACE)
    bars(axis, groups, series, fmt="{:.1f}")
    style(axis, "Energy efficiency - measured on battery, never while charging",
          "Tokens per watt")
    axis.legend(frameon=False, fontsize=9, ncol=3, loc="upper right")
    fig.text(0.06, 0.04,
             "Charging rows are excluded: USB input invalidates the battery-current reading, "
             "so the app hides power rather than reporting a wrong number.",
             fontsize=8, color=INK_MUTED)
    fig.subplots_adjust(left=0.085, right=0.98, top=0.90, bottom=0.20)
    fig.savefig(OUT / "energy_efficiency.png", dpi=180, facecolor=SURFACE)
    plt.close(fig)
    return "energy_efficiency.png"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = load()
    written = [name for name in (decode_attribution(rows), kleidiai(rows), energy(rows)) if name]
    print(f"wrote {len(written)} plots to {OUT}: {', '.join(written)}")
    skipped = [r["model_gguf"] for r in rows if num(r["threads_only_decode_tok_s"]) is None]
    if skipped:
        print(f"skipped {len(skipped)} two-arm row(s) with no threads-only measurement: "
              f"{', '.join(sorted(set(skipped)))}")


if __name__ == "__main__":
    main()
