#!/usr/bin/env python3
"""Create CPU, frequency, thermal, power, memory and summary plots from an ENTITY benchmark CSV.

Usage:
    python3 benchmarks/plot_telemetry.py entity_bench_123.csv benchmarks/plots

Requires matplotlib (`python3 -m pip install matplotlib`). The input must be the unmodified CSV
exported by ENTITY's Benchmark screen. Three-arm exports (naive / threads_only / optimized) also
produce the frequency trace and the summary comparison; older two-arm exports still plot, minus
whatever they did not record.

Nothing here invents data. Every series is drawn from sample_* rows in the CSV; if a metric is
absent (an OEM kernel that hides scaling_cur_freq, or a charging phone with no valid power), the
corresponding plot is skipped and said so on stdout.
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


THERMAL_LABELS = {
    0: "NONE",
    1: "LIGHT",
    2: "MODERATE",
    3: "SEVERE",
    4: "CRITICAL",
    5: "EMERGENCY",
    6: "SHUTDOWN",
}

# Benchmark arms, in the order the app runs them. Colour is stable across every plot so the
# same arm is the same colour in the frequency, power and thermal charts.
ARMS = {
    "naive": ("Naive (8 threads, all cores)", "#c44e52"),
    "threads_only": ("Threads only (no pinning)", "#dd8452"),
    "optimized": ("ENTITY Auto (pinned)", "#4c72b0"),
}

FREQ_METRIC = re.compile(r"^sample_cpu(\d+)_freq$")


def read_csv(path: Path):
    """Return (traces, meta, aggregates) from an ENTITY benchmark export.

    traces:     {(config, pass): [ {metric: value, ...}, ... ]} ordered by elapsed time
    meta:       {key: raw string}
    aggregates: {(config, stat): {metric: value}}  e.g. ("optimized", "median")
    """
    samples = defaultdict(lambda: defaultdict(dict))
    meta: dict[str, str] = {}
    aggregates: dict[tuple[str, str], dict[str, float]] = defaultdict(dict)

    with path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            config = (row.get("config") or "").strip()
            metric = (row.get("metric") or "").strip()
            index = (row.get("run_index") or "").strip()
            raw = row.get("value")

            if config == "meta":
                meta[metric] = raw
                continue
            if config not in ARMS:
                continue

            try:
                value = float(raw)
            except (TypeError, ValueError):
                continue

            if index in {"median", "stddev"}:
                aggregates[(config, index)][metric] = value
            elif metric.startswith("sample_") and ":" in index:
                pass_index, sample_index = index.split(":", 1)
                samples[(config, pass_index)][sample_index][metric] = value

    traces = {}
    for key, by_index in samples.items():
        points = [p for p in by_index.values() if "sample_elapsed" in p]
        traces[key] = sorted(points, key=lambda p: p["sample_elapsed"])
    return traces, meta, aggregates


def arm_style(config):
    label, colour = ARMS[config]
    return label, colour


def plot_metric(traces, metric, title, ylabel, output):
    """One line per arm; repeated passes of the same arm share its colour."""
    fig, axis = plt.subplots(figsize=(9, 4.8), constrained_layout=True)
    seen = set()
    drawn = False
    for (config, pass_index), points in sorted(traces.items()):
        xs = [p["sample_elapsed"] / 1000.0 for p in points if metric in p]
        ys = [p[metric] for p in points if metric in p]
        if not xs:
            continue
        label, colour = arm_style(config)
        axis.plot(
            xs, ys, linewidth=1.8, color=colour, alpha=0.9,
            label=label if config not in seen else None,
        )
        seen.add(config)
        drawn = True
    if not drawn:
        plt.close(fig)
        return False
    axis.set(title=title, xlabel="Elapsed time in pass (s)", ylabel=ylabel)
    axis.grid(alpha=0.25)
    axis.legend()
    fig.savefig(output, dpi=180)
    plt.close(fig)
    return True


def plot_frequency(traces, meta, output):
    """Per-core live clock. This is the plot that shows the optimization physically happening:
    under ENTITY Auto the performance cores sit near their ceiling and the little cores idle;
    under the naive arm the work is smeared across every core."""
    perf = {int(c) for c in (meta.get("perf_cores") or "").split() if c.isdigit()}
    if not any(FREQ_METRIC.match(m) for pts in traces.values() for p in pts for m in p):
        return False

    configs = [c for c in ARMS if any(cfg == c for cfg, _ in traces)]
    fig, axes = plt.subplots(
        1, len(configs), figsize=(5.2 * len(configs), 4.8),
        sharey=True, constrained_layout=True, squeeze=False,
    )

    for axis, config in zip(axes[0], configs):
        # First pass of this arm is representative; plotting all passes would be unreadable.
        key = sorted(k for k in traces if k[0] == config)[0]
        points = traces[key]
        cores = sorted({
            int(FREQ_METRIC.match(m).group(1))
            for p in points for m in p if FREQ_METRIC.match(m)
        })
        for core in cores:
            metric = f"sample_cpu{core}_freq"
            xs = [p["sample_elapsed"] / 1000.0 for p in points if metric in p]
            ys = [p[metric] for p in points if metric in p]
            if not xs:
                continue
            is_perf = core in perf
            axis.plot(
                xs, ys, linewidth=1.6 if is_perf else 1.0,
                color="#4c72b0" if is_perf else "#b0b0b0",
                alpha=0.95 if is_perf else 0.7,
                label=f"cpu{core} {'perf' if is_perf else 'little'}",
            )
        axis.set(title=arm_style(config)[0], xlabel="Elapsed time in pass (s)")
        axis.grid(alpha=0.25)
        axis.legend(fontsize=7, ncol=2)
    axes[0][0].set_ylabel("Core clock (MHz)")
    fig.suptitle("CPU frequency per core - blue = performance cluster, grey = efficiency cluster")
    fig.savefig(output, dpi=180)
    plt.close(fig)
    return True


def plot_thermal(traces, output):
    fig, temp_axis = plt.subplots(figsize=(9, 4.8), constrained_layout=True)
    status_axis = temp_axis.twinx()
    seen = set()
    drawn = False
    for (config, pass_index), points in sorted(traces.items()):
        xs = [p["sample_elapsed"] / 1000.0 for p in points]
        temps = [p.get("sample_battery_temp") for p in points]
        statuses = [p.get("sample_thermal_status") for p in points]
        label, colour = arm_style(config)
        if xs and all(v is not None for v in temps):
            temp_axis.plot(
                xs, temps, linewidth=1.8, color=colour,
                label=f"{label} battery" if config not in seen else None,
            )
            drawn = True
        if xs and all(v is not None for v in statuses):
            status_axis.step(
                xs, statuses, where="post", linestyle="--", alpha=0.6, color=colour,
                label=f"{label} thermal state" if config not in seen else None,
            )
        seen.add(config)
    if not drawn:
        plt.close(fig)
        return False
    temp_axis.set(
        title="Thermal analysis - battery temperature against Android's reported thermal state",
        xlabel="Elapsed time in pass (s)", ylabel="Battery temperature (C)",
    )
    status_axis.set_ylabel("Android thermal status")
    status_axis.set_yticks(list(THERMAL_LABELS))
    status_axis.set_yticklabels([THERMAL_LABELS[k] for k in THERMAL_LABELS])
    temp_axis.grid(alpha=0.25)
    handles, labels = temp_axis.get_legend_handles_labels()
    rh, rl = status_axis.get_legend_handles_labels()
    if handles or rh:
        temp_axis.legend(handles + rh, labels + rl, loc="best", fontsize=8)
    fig.savefig(output, dpi=180)
    plt.close(fig)
    return True


def plot_summary(aggregates, output):
    """Decode throughput and energy efficiency per arm, median +/- population sd.

    With three arms this is the attribution chart: naive -> threads_only is what the thread
    count earns, threads_only -> optimized is what the core pinning adds on top.
    """
    configs = [c for c in ARMS if (c, "median") in aggregates]
    if len(configs) < 2:
        return False

    def series(metric):
        med = [aggregates[(c, "median")].get(metric, 0.0) for c in configs]
        sd = [aggregates.get((c, "stddev"), {}).get(metric, 0.0) for c in configs]
        return med, sd

    tg, tg_sd = series("tg")
    eff, eff_sd = series("tok_per_w")
    has_power = any(v > 0 for v in eff)

    panels = 2 if has_power else 1
    fig, axes = plt.subplots(1, panels, figsize=(6.0 * panels, 4.8), constrained_layout=True, squeeze=False)
    labels = [arm_style(c)[0].split(" (")[0] for c in configs]
    colours = [arm_style(c)[1] for c in configs]

    axes[0][0].bar(labels, tg, yerr=tg_sd, capsize=5, color=colours)
    axes[0][0].set(title="Decode throughput", ylabel="tokens / s")
    for i, v in enumerate(tg):
        axes[0][0].text(i, v, f"{v:.1f}", ha="center", va="bottom", fontweight="bold")

    if has_power:
        axes[0][1].bar(labels, eff, yerr=eff_sd, capsize=5, color=colours)
        axes[0][1].set(title="Energy efficiency", ylabel="tokens / watt")
        for i, v in enumerate(eff):
            axes[0][1].text(i, v, f"{v:.1f}", ha="center", va="bottom", fontweight="bold")

    for axis in axes[0]:
        axis.grid(alpha=0.25, axis="y")
        axis.tick_params(axis="x", labelsize=8)

    if len(configs) == 3 and tg[0] > 0 and tg[1] > 0:
        threads = (tg[1] / tg[0] - 1) * 100
        pinning = (tg[2] / tg[1] - 1) * 100
        fig.suptitle(
            f"Thread count alone: {threads:+.0f}% decode   |   "
            f"pinning those threads adds: {pinning:+.0f}%"
        )
    fig.savefig(output, dpi=180)
    plt.close(fig)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="ENTITY benchmark CSV export")
    parser.add_argument("output_dir", type=Path, help="directory for PNG plots")
    args = parser.parse_args()

    traces, meta, aggregates = read_csv(args.csv)
    if not traces:
        parser.error("no sample_* telemetry found; export a benchmark from the app")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    arms = sorted({config for config, _ in traces})
    print(f"arms found: {', '.join(arms)}")
    if "threads_only" not in arms:
        print("note: two-arm export. The threads-only ablation arm is missing, so the")
        print("      attribution chart cannot separate thread count from core pinning.")

    written = []
    plots = [
        (plot_metric, ("sample_process_cpu", "CPU utilization", "App process CPU (%)", args.output_dir / "cpu_utilization.png")),
        (plot_metric, ("sample_power", "Power consumption", "Battery power (W)", args.output_dir / "power_consumption.png")),
        (plot_metric, ("sample_free_ram", "Memory availability", "Free RAM (GiB)", args.output_dir / "memory_usage.png")),
    ]
    for func, rest in plots:
        metric, title, ylabel, out = rest
        if func(traces, metric, title, ylabel, out):
            written.append(out.name)
        else:
            print(f"skipped {out.name}: no {metric} rows in this CSV")

    if plot_frequency(traces, meta, args.output_dir / "cpu_frequency.png"):
        written.append("cpu_frequency.png")
    else:
        print("skipped cpu_frequency.png: no sample_cpuN_freq rows "
              "(this kernel does not expose scaling_cur_freq, or the export predates frequency sampling)")

    if plot_thermal(traces, args.output_dir / "thermal_analysis.png"):
        written.append("thermal_analysis.png")
    if plot_summary(aggregates, args.output_dir / "summary_comparison.png"):
        written.append("summary_comparison.png")

    print(f"\nwrote {len(written)} plots to {args.output_dir}: {', '.join(written)}")


if __name__ == "__main__":
    main()
