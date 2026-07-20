# ENTITY v3.0.1 — 2026-07-20

**APK:** `ENTITY-v14-metrics-sampling-fix-20260720-release.apk` (release)

**Performance fix: in-chat decode speed with live metrics visible.** With the metrics graph (or
stats bar) enabled, chat decode dropped from ~18 to ~14 tok/s on the reference phone. The cause was
not the graph's colors: the metrics pipeline ran once per generated token on the main thread —
three binder IPC calls per token (battery intent, current draw, memory info) plus a full
seven-series graph redraw — and that work competed for the same big cores the four decode threads
are pinned to. Standalone bench numbers were never affected; the engine is untouched.

## Fixed

- **Live metrics sample on a fixed 500 ms clock instead of per token.** Battery/memory reads and
  graph redraws drop from ~18/s to 2/s during generation, returning in-chat decode to
  benchmark-level speed with the graph visible.
- The metrics graph window is now time-based: 120 samples × 500 ms ≈ the last 60 seconds,
  instead of the last 120 tokens.
- Final stats bar / header chips refresh once at generation end, so the displayed tok/s is exact
  rather than up to half a second stale.

## Upgrade notes

- No inference-path changes: thread derivation, core pinning and every published benchmark number
  carry over unchanged.
- Preferences, conversations, KV session files and imported models all carry over in place
  (versionCode 9 → 10, same signing key): `adb install -r` upgrades without uninstalling.
