# ENTITY v3.0.2 — 2026-07-20

**APK:** `ENTITY-v15-benchmark-thread-derivation-20260720-release.apk` (release)

**The in-app benchmark derived its thread count from a stale rule, and a flagship exposed it.**
v2.4.0 moved Auto's generation thread count to a topology rule — the cores whose
`cpuinfo_max_freq` sits within 10% of the fastest — and raised the clamp from 4 to 6. The native
engine was changed; the benchmark screen's copy of that rule was not. It kept computing
`online cores − 2`, which returns the same 4 on a 4+4 phone only because the old clamp happened to
be 4. On a 2+6 flagship (Galaxy S26 Ultra: 6× 3.628 GHz + 2× 4.742 GHz) the native side derives
**2** threads while the benchmark's copy returned **6** — so the threads-only arm ran six threads
against an Auto arm running two, and the exported CSV recorded the wrong number for both.

**Chat and inference speed are unaffected.** The stale rule lived only in `BenchmarkActivity`; real
generation always went through `init_context()` in `ai_chat.cpp`, correct since v2.4.0. This changes
what the benchmark measures and reports, not how the app runs.

## Fixed

- **The threads-only arm now holds the thread count at Auto's real value.** It delegates to
  `DeviceOptimizer.topClusterCoreCount()` — the same top-frequency-cluster rule the native side and
  the standalone Bench app already use — instead of restating it. On any device where the two
  disagreed, naive → threads-only → Auto changed two variables between the second and third arm,
  which is the exact attribution error the three-arm design exists to prevent.
- **Exported CSV metadata now reports the thread count the engine actually used.**
  `threads_optimized` was written from the stale mirror, so an export could claim six threads for a
  run that executed on two.
- Added the 2+6 flagship case to `DeviceOptimizerTest`, the topology the v2.4.0 notes flagged as
  expected but untested.

## Upgrade notes

- **Unaffected on 4+4 devices.** Both rules returned 4 there, so every published CMF Phone 1 and
  OPPO CPH2729 result carries over unchanged. Those exports were taken with the standalone Bench
  app, which never had this bug.
- No inference-path changes: thread derivation for chat, core pinning, and the KleidiAI path are
  untouched.
- Preferences, conversations, KV session files and imported models carry over in place
  (versionCode 10 → 11, same signing key): `adb install -r` upgrades without uninstalling.

## Known limits

- The thread count is still derived from **clock frequency alone**, which cannot distinguish a
  Cortex-A55 at 80% of the prime clock from a full performance core at 76% of it. On the topologies
  measured so far this produces the right answer; on an untested tri-cluster design it may be
  narrower than optimal. See `docs/OPTIMIZATIONS.md`.
