# ENTITY v1.3.0 — 2026-07-03

**APK:** `ENTITY-v3-benchmark-20260703-2118.apk`

This release turns ENTITY's core claim into something you can measure inside the app. The whole point of
the project is that pinning inference to the Cortex-A78 big cores beats a naïve all-cores run on this
chip — but that was only provable from the command line. The new in-app benchmark runs the exact same
workload (PP 512 / TG 128) twice back-to-back on the loaded model, once naïve across all eight cores and
once with ENTITY's four-big-core optimization, then shows both side by side with the speed delta. It uses
the same prompt-processing / token-generation framing that Arm AI Chat and PocketPal report, so the
numbers are directly comparable — but it adds the axis they don't measure at all: **power draw in watts
and tokens-per-watt**, reframing on-device AI as an efficiency problem and not just a speed one. A latent
correctness bug was fixed in the process, where the benchmark's throwaway context could corrupt the live
chat's context bounds.

## Added
- **In-app benchmark** (⋮ → Benchmark) — runs the same PP 512 / TG 128 test twice on the loaded model,
  **naïve** (8 threads, all cores) vs **optimized** (4 threads pinned to the Cortex-A78 big cluster),
  and reports prompt/decode tok/s, **power (W)**, and **tokens-per-watt**, with a copyable result. Same
  PP/TG framing Arm AI Chat and PocketPal use, plus the energy axis they omit.

## Fixed
- **Benchmark corrupted the chat's context bounds** — `benchModel` builds its own small context, which
  overwrote the global context-size tracker; it now saves/restores it, so running a benchmark no longer
  affects the loaded chat.
