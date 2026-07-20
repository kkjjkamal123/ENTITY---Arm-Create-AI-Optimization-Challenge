# Prebuilt APKs

One prebuilt debug or release-signed APK per version, ready to install on any arm64-v8a Android 13+ device.

| File | Version | Build | Size |
|---|---|---|---|
| `ENTITY-v15-benchmark-thread-derivation-20260720-release.apk` | **3.0.2** (current) | release-signed | 10.3 MB |
| `ENTITY-v14-metrics-sampling-fix-20260720-release.apk` | 3.0.1 | release-signed | 10.3 MB |
| `ENTITY-v13-mono-ui-refresh-20260718-release.apk` | 3.0.0 | release-signed | 10.3 MB |
| `ENTITY-v12-kv-session-adaptive-threads-20260717-release.apk` | 2.4.0 | release-signed | 10.3 MB |
| `ENTITY-v11-ui-polish-20260715-release.apk` | 2.3.0 | release-signed | 10.3 MB |
| `ENTITY-v10-sustained-thermal-20260715-release.apk` | 2.2.0 | release-signed | 10.3 MB |
| `ENTITY-v9-kleidiai-quant-20260714-release.apk` | 2.1.0 | release-signed | |
| `ENTITY-v9-kleidiai-quant-20260714-debug.apk` | 2.1.0 | debug | |
| `ENTITY-v8-universal-arm-20260712-1240-release.apk` | 2.0.0 | release-signed | 9.8 MB |
| `ENTITY-v8-universal-arm-20260712-1240-debug.apk` | 2.0.0 | debug |  |
| `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` | 1.7.0 | release-signed | 7.0 MB |
| `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` | 1.7.0 | debug | 40.3 MB |
| `ENTITY-v6-chats-uipolish-20260710-2213.apk` | 1.6.0 | debug | |
| `ENTITY-v6-chats-Stripped.apk` | (size experiment) | debug | |
| `ENTITY-v5-ui-emptystate-20260704-1610.apk` | 1.5.0 | debug | |
| `ENTITY-v4-icon-chips-20260704-1259.apk` | 1.4.0 | debug | |
| `ENTITY-v3-benchmark-20260703-2118.apk` | 1.3.0 | debug | |
| `ENTITY-v2-modelinfo-progress-20260703-2048.apk` | 1.2.0 | debug | |
| `ENTITY-v1-runtime-graph-settings-20260703-1521.apk` | 1.1.0 | debug | |
| `ENTITY-optimized-single-variant-20260702-2335.apk` | 1.0.0 | debug | |

## Standalone benchmark app

`ENTITY-Bench` is a separate, dedicated benchmark app (no chat) that runs ENTITY's three-arm ablation - naive, threads-only and auto - on a model you import, autosaves every result on the device, and exports every pass to CSV. Anyone with an arm64 phone can install it, run the ablation on their own SoC, and contribute a row to [`benchmarks/device-result-template.csv`](../benchmarks/device-result-template.csv). See [app/entity.bench.android/README.md](../app/entity.bench.android/README.md).

| File | Version | Build | Size |
|---|---|---|---|
| `ENTITY-Bench-v1.2.0-release.apk` | **1.2.0** (current) | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.1.0-release.apk` | 1.1.0 | release-signed | 9.7 MB |
| `ENTITY-Bench-v1.0.0-release.apk` | 1.0.0 | release-signed | 9.9 MB |

v1.2.0 adds a **thread sweep**: every thread width the device can use, each one pinned to that many
of its fastest cores and again left to the scheduler, with the winning configuration named. The
three-arm ablation asks whether the shipped policy beats the phone's default; the sweep asks whether
the shipped policy is the best that phone can do, and answers it per device rather than from a table
of core types that ages with every new SoC.

v1.1.0 is a ground-up rebuild as a purpose-built benchmark instrument: its own home / live-run /
result screens instead of the chat app's benchmark page, every result autosaved with a browsable
history (any past run can be reopened and its CSV exported later), a pure black-and-white
theme with System / Light / Dark selection in Settings, a new pixel-art launcher icon, and an
optional fourth arm, "efficiency cores": auto's thread count pinned to the slowest cluster,
exported as `affinity_efficiency` in the CSV, to measure whether the efficiency cores are
actually more energy-efficient (tok/W) for LLM decode or just slower. CSV row keys are unchanged
from v1.0.0.

## Start here

**Recommended**: `ENTITY-v15-benchmark-thread-derivation-20260720-release.apk` is the current release (v3.0.2), release-signed. It corrects the in-app benchmark's generation thread count, which restated the topology rule instead of using it and so diverged from the engine on devices with more than four performance cores; chat speed is unchanged. On top of v3.0.1's fixed-interval metrics sampling, v3.0.0's MONO UI remake, and v2.4.0's inference path: 7 Arm CPU backend variants with automatic runtime selection, KV-cache session reuse, and a topology-derived thread count.

## Installation

All APKs are **arm64-v8a only** and require Android 13+. Install with:

```bash
adb install -r <filename>.apk
```

Then launch the app and import a GGUF model: tap the model line in the header (or MODEL in the menu drawer) and choose Import from device.

## Build signatures

- **v1.7.0 onward** ship a properly release-signed APK (`CN=ENTITY`), along with a debug build for testing.
- **All earlier versions** (v1.6.0 and before) are debug-signed only; these remain suitable for offline use and competitive evaluation, but lack Play Store release eligibility.
