# ENTITY v2.2.0 — 2026-07-15

**APK:** `ENTITY-v10-sustained-thermal-20260715-release.apk` (release)

The regular benchmark measures a cool phone for one pass. A phone is not cool for long. This
release adds a **sustained thermal benchmark**: back-to-back PP 512 / TG 128 passes for a
selectable 2, 5 or 10 minutes per arm, deliberately without the inter-pass cooldown the regular
benchmark uses, so the result shows how each configuration degrades as the SoC heats up.

## Added

- **Sustained benchmark mode** on the benchmark screen. Threads-only and Auto each run
  back-to-back passes for the selected duration (2 / 5 / 10 minutes per arm, 5 default). No
  cooldown between passes: heat is the variable under test.
- **Per-pass telemetry** for every sustained pass: decode tok/s, Android thermal status, battery
  temperature and power, shown in the results table and exported in the CSV.
- The sustained CSV gains a per-pass **power (W)** row; the headline and notes report the actual
  number of passes each arm completed in its window.

## Changed

- The sustained loop is time-bounded rather than a fixed pass count: passes repeat until the
  selected duration elapses (always at least one), keeping the existing 2 s inter-pass gap.

Run it from the benchmark screen on an unplugged phone. A 10-minute selection runs about 10
minutes per arm, so budget 20+ minutes total.
