# ENTITY Bench v1.1.0 — dedicated benchmark app rebuild

**APK:** `apk/ENTITY-Bench-v1.1.0-release.apk` (release-signed, arm64-v8a, Android 13+)
**Source:** `app/entity.bench.android/` · applicationId `com.entity.bench` · versionCode 3

v1.0.0 was ENTITY's chat app with the benchmark screen left in. v1.1.0 is a ground-up
rebuild around one job: run a controlled CPU benchmark for local LLMs, save the result,
and let you come back to it.

## What changed

- **Dedicated flow.** Home (device under test, model, run config) -> live run screen
  (per-arm status, cooldown countdown, progress, live temperature / power / thermal /
  app-CPU readouts, abort) -> result page. The chat app's benchmark page is gone.
- **Results autosave.** Every finished run is written to device storage the moment it
  completes. The home screen shows the last result and recent history; **All results**
  lists every saved run. Any past result reopens as a full page and its raw per-pass CSV
  can be exported at any time - not just right after the run.
- **"Open full result" opens the saved result page**, never a fresh benchmark screen.
- **Two-color theme.** Pure black and pure white, inverted between light and dark - no
  grays, square corners, monospace throughout. Theme selection (System / Light / Dark)
  lives in Settings.
- **New launcher icon** - the pixel-art E/BENCH mark.
- **Efficiency-cores arm** (carried from the pre-rebuild v1.1.0 work): an optional fourth
  ablation arm pinning auto's thread count to the slowest cluster, exported as
  `affinity_efficiency`, answering whether the little cores are more energy-efficient
  (tok/W) for decode or only slower.

## What did not change

- The measurement core: PP 512 / TG 128, discarded warm-up, cooldown to baseline before
  every pass (at least 15 s, up to 90 s, 0.5 C margin, 37.5 C floor), naive ->
  threads-only -> auto order, median ± population stddev, power hidden while charging.
- The sustained mode: 2/5/10 min of back-to-back passes per arm with a 2 s gap, heat
  accumulating inside a block.
- The CSV format: row keys and meta names match the v1.0.0 exporter, so existing analysis
  scripts keep working. Results are additionally stamped with the app version that
  produced them.

## Install

```bash
adb install -r apk/ENTITY-Bench-v1.1.0-release.apk
```

Upgrades in place over v1.0.0 (same release signature). Results are stored in the app's
private storage; uninstalling removes them.
