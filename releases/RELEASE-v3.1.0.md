# ENTITY v3.1.0 - 2026-07-21

**APK:** `ENTITY-v17-bench-history-20260721-release.apk` (release)

**Benchmark history: every run the chat app finishes is now kept on the phone.** The in-app
benchmark could produce a result and lose it - navigating away, or the system reclaiming the
activity behind the file picker, left nothing behind, and the only way to keep a run was to export
its CSV in that moment. The standalone Bench app has kept a browsable history since v1.1.0; the
chat app now does the same, so both apps behave the same way after a run finishes.

## Added

- **Autosaved benchmark history** (`BenchHistory`, `BenchHistoryActivity`). Both the three-arm
  ablation and the sustained thermal test write two files the moment they complete - the same
  per-pass CSV the export button builds and the same summary the COPY button emits - plus one
  summary line in an `index.jsonl` the list reads. There is no save button to forget.
- **A history screen**, reachable from the drawer (TOOLS → BENCHMARK HISTORY) and from the
  benchmark screen itself. Newest first, showing model, date, arm count or sustained duration,
  charging state, and the decode delta over naive. Tap opens the saved run, long-press deletes;
  a saved run can be copied or re-exported to CSV at any time, and there is a delete-all.
- **Re-export is a file copy.** The CSV is written at save time, so exporting an older run cannot
  hit the empty-file failure the live export had to be hardened against in v2.1.0 - there is no
  in-memory result left to lose.

## Fixed

- **Back buttons on Settings, About, Benchmark and History now meet the 48 dp touch-target
  minimum** and carry a content description, so they no longer announce as "less than" to
  TalkBack. They were 14 sp glyphs with 6 dp of vertical padding, about 31 dp tall.

## Upgrade notes

- No inference-path, thread-derivation or pinning changes; every published benchmark result
  carries over. History starts empty - runs completed before this version were never stored.
- Saved runs live in the app's private storage and are included in the app's backup rules; they
  never leave the phone.
- Preferences, conversations, KV session files and imported models carry over in place
  (versionCode 12 -> 13, same signing key): `adb install -r` upgrades without uninstalling.
