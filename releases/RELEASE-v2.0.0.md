# ENTITY v2.0.0 — 2026-07-12

**APK:** `ENTITY-v8-universal-arm-20260712-1240-debug.apk` (debug) · `ENTITY-v8-universal-arm-20260712-1240-release.apk` (release, ~9.8 MB)

The headline: **universal Arm support via runtime CPU backend dispatch**. Previously ENTITY shipped
exactly one CPU backend compiled for a single known SoC architecture. This meant it would have crashed
with SIGILL on older Arm phones lacking certain ISA features, and never used faster kernels on newer
chips that have them. v2.0.0 ships **7 Arm CPU backend variants** and uses ggml's dynamic loader to
select the best one the phone's CPU actually supports at startup.

The variants (with KleidiAI kernels in each):
`android_armv8.0_1` (baseline, pre-dotprod SoCs) · `android_armv8.2_1` (DOTPROD) ·
`android_armv8.2_2` (DOTPROD+FP16) · `android_armv8.6_1` (DOTPROD+FP16+i8mm) · `android_armv9.0_1` (+SVE2) ·
`android_armv9.2_1` and `android_armv9.2_2` (+SVE/SME).

The core optimization — big-core affinity via live `cpufreq` ranking — was already
SoC-agnostic and remains unchanged. The backend dispatch is wired via `GGML_CPU_ALL_VARIANTS` and
`GGML_BACKEND_DL`; the app was already built with these flags, so the change was enabling the flag
and recompiling. **No performance regression on the existing dotprod path** — KleidiAI is compiled
into every variant, validated. Still arm64-v8a only.

## First-run "Optimize for your device" dialog

On first launch, a dialog detects the device's performance-core count, clock speed, efficiency-core
count, free/total RAM, and the ISA features the loaded backend actually supports (e.g., i8mm, dotprot).
It *suggests* optimization but does not decide — a user can dismiss it with "Not now" and re-run it
any time from Settings. The "Optimize" button simply enables Auto mode and turns off Efficiency mode;
it does not change the model, thread count, or context size.

## Power measurement bug fixed and cross-device validation complete

Android's `BATTERY_PROPERTY_CURRENT_NOW` is documented in microamps, but many OEM kernels —
Qualcomm ones especially — report in milliamps instead. ENTITY used to assume microamps, so on
affected devices power was under-reported by 1000× and tokens-per-watt was over-reported by 1000×.
A new `PowerMath` helper resolves the unit by physical plausibility (phones draw ~0.05–15 W) instead
of trusting the documented unit. A second bug was also fixed: `getIntProperty`'s unsupported sentinel
(`Int.MIN_VALUE`) was feeding garbage into the on-screen power graph on devices that don't support
the property. **This affects only the power/efficiency readout on affected devices.** It does NOT
change tok/s figures.

**Cross-device validation:** the core big-core affinity optimization was independently measured on a
second device (Qualcomm Snapdragon 6 Gen 4, 4 perf cores @2.30 GHz + 4 eff cores @1.80 GHz) using
`Llama-3.2-1B-Instruct-Q3_K_L` (the same model as the reference device) under the full protocol
(PP 512 / TG 128, 3 runs, median ± stddev, cooldown). Results:

| Metric | Naïve (8 cores) | Optimized (4 perf) | Gain |
|---|---:|---:|---:|
| Decode throughput | 6.0 ± 1.1 tok/s | 13.1 ± 0.05 tok/s | +117% |
| Energy efficiency | 1.8 ± 0.24 tok/W | 3.8 ± 0.31 tok/W | **2.1×** |

**Reference device (CMF Phone 1, MediaTek Dimensity 7300):** +121% decode, 2.5× efficiency.
**Second device (Snapdragon 6 Gen 4):** +117% decode, 2.1× efficiency.
**This is the strongest evidence in the project: the core optimization reproduces across two different
SoC vendors on the same model, under the same protocol, with no vendor-specific code.**

## Benchmark now measures the shipped configuration

The in-app benchmark's "Optimized" column previously used an explicit 4-thread config and called it
"what ENTITY ships" — but the shipped app runs in Auto mode. More significantly, the native
`benchModel` path never attached the split thread pools, so **prompt processing was confined to the
performance cores** instead of using all cores as the design intends. The benchmark now attaches the
same split thread pools (generation on big cores, prompt processing on all cores) and runs ENTITY's
real Auto configuration. The naive control (8 explicit threads, all cores) is unchanged, and the
protocol (PP 512 / TG 128, runs per config, cooldown, median ± stddev) is unchanged.

## UI strings are now SoC-neutral

Strings that hardcoded "Cortex-A78" and "dotprod" were wrong on non-MediaTek phones. The model-info
line now reports the **actually detected** performance-core count and active ISA features (e.g.
`Compute: CPU · 4 perf cores · i8mm, dotprod · KleidiAI`).

## Verification

- `./gradlew :app:assembleDebug :app:assembleRelease` → **BUILD SUCCESSFUL**.
- `./gradlew :app:testDebugUnitTest` → **19 tests, 0 failures** (ThermalGuard 5, DeviceOptimizer 8, PowerMath 6).
- Release APK verified with `apksigner` → signed by `CN=ENTITY, OU=Mobile, O=ENTITY, L=Unknown, ST=Unknown, C=IN`.
- Release APK contains all 7 `libggml-cpu-android_*.so` variants plus `libkleidiai.so`.
