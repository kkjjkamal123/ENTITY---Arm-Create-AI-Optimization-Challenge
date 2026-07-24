# Prebuilt APKs

One prebuilt debug or release-signed APK per version, ready to install on any arm64-v8a Android 13+ device.

| File | Version | Build | Size |
|---|---|---|---|
| `ENTITY-v23-repeat-penalty-20260724-release.apk` | **3.6.1** (current) | release-signed | 10 MB |
| `ENTITY-v22-adpf-power-fix-20260723-release.apk` | 3.6.0 | release-signed | 10 MB |
| `ENTITY-v21-prefill-threads-placement-latex-20260723-release.apk` | 3.5.0 | release-signed | 10 MB |
| `ENTITY-v20-edge-insets-20260722-release.apk` | 3.4.1 | release-signed | 10.4 MB |
| `ENTITY-v19-models-screen-colour-20260722-release.apk` | 3.4.0 | release-signed | 10.4 MB |
| `ENTITY-v18-model-catalog-20260722-release.apk` | 3.2.0 | release-signed | 10.4 MB |
| `ENTITY-v17-bench-history-20260721-release.apk` | 3.1.0 | release-signed | 10.4 MB |
| `ENTITY-v16-ui-perf-20260720-release.apk` | 3.0.3 | release-signed | 10.3 MB |
| `ENTITY-v15-benchmark-thread-derivation-20260720-release.apk` | 3.0.2 | release-signed | 10.3 MB |
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
| `ENTITY-Bench-v2.1.1-release.apk` | **2.1.1** (current) | release-signed | 9.9 MB |
| `ENTITY-Bench-v2.1.0-release.apk` | 2.1.0 | release-signed | 9.9 MB |
| `ENTITY-Bench-v2.0.0-release.apk` | 2.0.0 | release-signed | 9.9 MB |
| `ENTITY-Bench-v1.5.0-release.apk` | 1.5.0 | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.4.0-release.apk` | 1.4.0 | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.3.0-release.apk` | 1.3.0 | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.2.1-release.apk` | 1.2.1 | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.2.0-release.apk` | 1.2.0 | release-signed | 10.2 MB |
| `ENTITY-Bench-v1.1.0-release.apk` | 1.1.0 | release-signed | 9.7 MB |
| `ENTITY-Bench-v1.0.0-release.apk` | 1.0.0 | release-signed | 9.9 MB |

v1.5.0 brings the bench app onto the chat app's colour system: pure black and white retired for Material's #121212 dark baseline and an off-white light paper, 1dp outline borders instead of 2dp at full text strength, a MONOCHROME / COLOUR palette switch in Settings, and edge-to-edge inset handling on every screen. It also detects **SME2** as its own ISA flag and reports an `sme2 kleidiai` chip - the shipped `android_armv9.2_2` variant already carried SVE2+SME and KleidiAI's SME2 microkernels, so those kernels always ran on SME2 silicon; the app simply never said so.

v1.4.0 adds the **model catalog**: a small curated list of Qwen2.5 and Llama 3.2 GGUFs, each row
tagged for the phone in hand (RECOMMENDED / GOOD FIT / FITS / TIGHT / TOO BIG) with a reason naming
the quantization and the ISA it actually reaches on that CPU, downloaded resumably so an
interrupted multi-GB transfer continues instead of restarting. It is the only feature that uses the
network, and only on an explicit tap. Measurement, arms, sweep and CSV schema are unchanged, so
v1.3.0 and v1.4.0 exports stay comparable.

v1.3.0 adds the **optimization indicator** on the device card: a chip per lever ENTITY ships, filled
only when that lever is live on the phone in hand and a dim dashed outline otherwise, gated on the
ISA flags in `/proc/cpuinfo`. It makes the difference between "ENTITY supports this" and "your phone
runs this" visible before a benchmark starts.

v1.2.1 is a UI-only pass with no measurement changes, so its exports stay directly comparable with
v1.2.0's: the bench app now shares the chat app's single 10 dp corner radius instead of staying
hard-square, "NO KLEIDIAI" is no longer painted as a solid inverted pill (solid inversion is this
design's emphasis, and the negative state was wearing it), importing a first model explains itself
the way the chat app does, and back buttons meet the 48 dp touch-target minimum.

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

**Recommended**: `ENTITY-v23-repeat-penalty-20260724-release.apk` is the current release (v3.6.1), release-signed. `penalty_repeat` sat at llama.cpp's own disabled default while ENTITY's `temp = 0.3` alone pushed the sampler close to greedy decoding - the textbook setup for both looping and bland replies. `penalty_repeat = 1.1` fixes that without touching temperature, so the factual grounding `temp = 0.3` was chosen for stays untouched; not yet confirmed on a device this session. On top of v3.6.0, which added an Android performance hint session - the one route an unprivileged app has into the kernel's scheduler and cpufreq machinery. `sched_setaffinity` says *where* work runs but not *how fast it must be*, so the kernel still reacts to load after the fact and a hard mask stops the platform migrating work as the phone heats up; a deadline hint lets the device decide, which is the right place for a decision that swung from -8.5% to +29.3% across contributed phones. It is shipped to be measured, not assumed - ENTITY Bench v2.1.0 carries an `adpf` arm and no speedup is claimed yet. It also fixes battery power being under-reported by 1,000,000x on devices that report `EXTRA_VOLTAGE` in volts rather than millivolts, which made watts and tokens-per-watt meaningless on an OPPO CPH2737. On top of v3.5.0, which fixed prompt processing running on two threads on every prime-core flagship - the thread count came from cores within 10% of the fastest clock, which on a chip with a prime core matches only the prime, so a Dimensity 7300 prefilled Llama-3.2-1B-Q4_0 at 139 tok/s while a stronger SM8550 with i8mm managed 111. Core detection now reads the kernel's own `cpu_capacity`, prefill runs on the whole performance cluster, and the decode thread count is deliberately unchanged. v3.5.0 also made core placement a user choice (Settings -> Inference: AUTO / PERF CORES / SCHEDULER) with the benchmark reporting which scheme won on your phone, and added LaTeX rendering with real stacked fractions and radicals. On top of v3.2.0, where a fresh install no longer needs you to go find a `.gguf` first - the model picker offers a curated catalog, tags each model for your phone (RECOMMENDED / GOOD FIT / FITS / TIGHT / TOO BIG) with the quantization and the ISA it will actually reach, downloads it resumably, and loads it when it finishes. Importing from storage still works exactly as before, and the network is touched only on an explicit tap. On top of v3.1.0, where every benchmark the app finishes is now saved on the phone automatically - the three-arm ablation and the sustained thermal test both write their per-pass CSV and summary the moment they complete - with a history screen (drawer → BENCHMARK HISTORY, or from the benchmark screen) to reopen, copy, re-export or delete any past run. Back buttons also now meet the 48 dp touch-target minimum and announce properly to TalkBack. No inference-path changes. On top of v3.0.3's frame-aware streaming repaint, v3.0.2's rounded corners and benchmark thread-count fix, v3.0.1's fixed-interval metrics sampling, v3.0.0's MONO UI remake, and v2.4.0's inference path: 7 Arm CPU backend variants with automatic runtime selection, KV-cache session reuse, and a topology-derived thread count.

## Installation

All APKs are **arm64-v8a only** and require Android 13+. Install with:

```bash
adb install -r <filename>.apk
```

Then launch the app and add a GGUF model: tap the model line in the header (or MODEL in the menu drawer) and choose either **Download a model...** (curated catalog, sized for your phone) or **Import from device...**.

## Build signatures

- **v1.7.0 onward** ship a properly release-signed APK (`CN=ENTITY`), along with a debug build for testing.
- **All earlier versions** (v1.6.0 and before) are debug-signed only; these remain suitable for offline use and competitive evaluation, but lack Play Store release eligibility.
