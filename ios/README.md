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
The CSV's power column is a fixed constant per arm — 4.00 W for naive, 2.20 for
threads-only, 3.10 for auto, 1.15 for efficiency — identical on every device and in every
run. `tok_per_watt` is therefore just decode throughput divided by a constant, and carries
no information decode throughput does not already carry. `app_cpu_pct` is likewise derived
(thread count × 100), not sampled.

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
   8-thread arm is the slowest. Here it is the fastest by a wide margin on all three
   devices. That is a statement about a synthetic Accelerate workload on a platform where
   the app cannot place threads — not about llama.cpp — but hiding it would be worse than
   explaining it.

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
