# ENTITY Bench for iOS

A SwiftUI port of ENTITY Bench's *structure* — the ablation arms, the thread sweep, the
sustained-run mode, the model-fit catalog — running on iPhone.

Read the next paragraph before reading any number this app produces.

## What it measures, and what it does not

**This app does not run a language model.** It has no llama.cpp, no ONNX runtime, and no
model weights. Its workload is a synthetic CPU-bound proxy built on Accelerate/vForce,
shaped like a PP512/TG128 pass. From the engine itself:

```swift
static let workloadDescription =
    "Synthetic CPU-bound proxy workload (PP512/TG128-shaped) using Accelerate/vForce, not real LLM inference"
```

Selecting a "model" only scales that synthetic workload's cost by a weight meant to
approximate that model size's relative compute. Nothing is downloaded and nothing is
inferred.

The app says so on every screen that could mislead — the run configuration screen, the
model catalog, the settings screen, the full result view, and the reference comparison.
That was the right call by whoever wrote it, and this README repeats it because a file in a
repository outlives the screen it came from.

**Its `power_w_est` and `tok_per_watt` columns are not measurements.** iOS does not expose
instantaneous power draw to an app; `battery.currentNowUa` is unavailable on the platform.
`BenchmarkEngine` computes `0.4 + workers × perThreadWatts` from a per-arm constant (0.6 for
naive and threads-only, 0.9 for auto, 0.25 for efficiency). That is a heuristic with no
sensor underneath it, and the app's own result view says so. It happens to read 4.00 / 2.20 /
3.10 / 1.15 W in every committed CSV only because all three phones tested have 6 cores; an
8-core device would put the naive arm at 5.20 W. `tok_per_watt` is that estimate divided into
decode throughput, and carries nothing decode throughput does not already carry.
`app_cpu_pct` is `min(cores × 100, busy-thread-seconds ÷ wall-time × 100)` — bounded by
worker count, not sampled from the OS.

Nothing in this directory should ever be quoted as an energy result. The energy claims this
project publishes come from Android, where the battery current is readable and integrated
over the measured power curve.

## Why it is in this repository

Two reasons, neither of them "here are more benchmark numbers".

1. **It is the control that makes the portability argument concrete.** ENTITY's thesis is
   that its speedups come from reading and steering Arm/Android hardware — core placement,
   cpufreq-ranked topology, per-core frequency, ADPF hints, energy telemetry, multi-variant
   v8.0–v9.2 backend dispatch. Apple silicon is also arm64 and exposes almost none of it.
   This port is what ENTITY looks like with those six mechanisms deleted, and the deletions
   are visible in the source rather than asserted in prose. See
   [`docs/PORTABILITY-ARM-VS-APPLE-SILICON.md`](../docs/PORTABILITY-ARM-VS-APPLE-SILICON.md).

2. **Its ablation inverts on iOS, and that is worth showing honestly.** On Android the naive
   arm — every core, maximum threads — is the slowest. Here it is the fastest on all three
   devices by 37% to 73%. Halving the worker count costs 27–42%, and the QoS class that
   stands in for affinity moves decode by less than 2% in either direction. That is a
   statement about a synthetic Accelerate workload on a platform where thread placement
   cannot be expressed — not about llama.cpp — but hiding it would be worse than explaining
   it.

## The arms

There is no `sched_setaffinity` on iOS, so the port substitutes QoS class. Worker counts are
relative to `activeProcessorCount`, which is 6 on all three phones tested — so the "naive"
arm here is 6 workers, not the 8 its Android namesake uses.

| Arm | Workers | QoS | What it isolates |
|---|---:|---|---|
| Naive | `activeProcessors` (6) | `.default` | the baseline |
| Threads Only | `max(2, n/2)` (3) | `.default` | worker count, against Naive |
| Entity Auto | `max(2, n/2)` (3) | `.userInteractive` | QoS placement, against Threads Only |
| Efficiency | `max(2, n/2)` (3) | `.background` | E-core confinement |

Two adjacent pairs each vary exactly one thing, which is what makes the inversion above
readable rather than merely surprising.

The measurements that *are* real inference on these phones come from a separate ONNX
translation build; its raw exports are in
[`../benchmarks/results/ios/`](../benchmarks/results/ios/).

## Contents

| Path | What |
|---|---|
| `app/MyApp/` | SwiftUI sources: engine, telemetry, models, views, store |
| `app/Entity Bench iOS.xcodeproj/` | Xcode project (per-user state stripped) |
| `ipa/EntityBench-1.1.ipa` | Build 1.1, 390 KB |
| `ipa/EntityBench-1.2.ipa` | Build 1.2, 580 KB |

The Xcode project was renamed from its default `Untitled Project`. Every occurrence of the
old name lived inside `/* … */` comments in `project.pbxproj`, which Xcode regenerates, and
the workspace refers to the project as `self:` — so the rename touches nothing structural.
The build target and product are still `MyApp`, which is what the project name does not
control.

This repository's owner develops on Windows; the iOS builds were produced on a Mac by a
collaborator, so the rename has not been verified by an actual Xcode open. If it ever fails
to load, the fix is to rename the directory back — nothing else was changed.

## Telemetry that iOS *does* give you

Worth recording, because the portability document leans on it: the app reads real thermal
state (`ProcessInfo.thermalState`), real battery level and charging state, real memory
footprint, and real disk capacity. What it cannot read is per-core frequency, instantaneous
current, per-core CPU ticks, or thread migrations — the same list the ONNX build reports
under `unavailable`.

Coarse thermal state and no power. That asymmetry is the finding.

## Known defects

Found by review of the committed source. None are fixed here, because this repository's
owner has no Mac and cannot compile a change to check it — so they are recorded rather than
patched blind, and anyone picking the project up should start here.

1. **`decode_stddev` / `prompt_stddev` are not standard deviations.**
   `BenchmarkEngine.standardDeviation(_:mean:)` is passed the *median* as its `mean:`
   argument, and divides by `n` rather than `n − 1` with `n = 3`. Squared deviation is
   minimised at the mean, so every value in every committed CSV is systematically inflated.
   The `±` figures in the result view come from the same call.
2. **`ReferenceData` mixes two measurement phases in one row.** The Android reference figures
   are taken from `benchmarks/termux_master_results.txt`, but `genTokensPerSecond` comes from
   the speed phase while `powerWatts` and `tokensPerWatt` come from the later
   sampler-enabled phase. The card renders "13.65 tok/s · 4.09 W · 1.98 tok/W", and
   13.65 ÷ 4.087 is 3.34, not 1.98. The 3B naive row is worse — 3.75 tok/s beside 0.16 tok/W,
   a figure derived from a decode rate of 0.7. Either carry one phase's numbers per row or
   drop the tok/W column.
3. **The thread sweep runs a duplicate point.** `sweepWorkerCounts.map { min($0, cores) }`
   turns the default `[2, 4, 6, 8]` into `[2, 4, 6, 6]` on a 6-core device, so the 6-worker
   pass runs twice and renders as two indistinguishable rows. Needs a dedupe after the clamp.
4. **The model-fit catalog sizes against total RAM, not jetsam headroom.**
   `ModelCatalogView` calls `fitTag(physicalMemoryGB:)`, which divides by
   `ProcessInfo.physicalMemory`. `Telemetry.availableMemoryMB()` wraps
   `os_proc_available_memory()` — the per-process limit that actually governs whether a load
   survives — but it is only recorded for display. This is the one place where the port is
   *less* careful than the Android original, whose catalog reads free memory.
5. **`ModelCatalogView` re-probes the device on every view init.** A `private let` calls
   `Telemetry.currentDeviceInfo()`, which runs `uname()`, toggles battery monitoring, and
   performs a filesystem capacity query — to use one field. SwiftUI re-initialises view
   structs freely. Read `ProcessInfo.processInfo.physicalMemory` directly.
