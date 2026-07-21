# ENTITY v3.0.3 - 2026-07-20

**APK:** `ENTITY-v16-ui-perf-20260720-release.apk` (release)

**Chat now measures whether the phone is keeping up, instead of assuming it can.** Streaming a
reply rebuilds the message's `StaticLayout` on every repaint - full text measurement and
line-breaking, on the main thread, competing with the four decode threads pinned to the same
cores. Previous versions repainted on a fixed 120 ms clock regardless of whether frames were
landing. A live frame-interval measurement (a `Choreographer` callback running only while
generating) now drives that interval directly: a phone holding its refresh rate stays at the
floor, one measurably missing frames backs off to a slower repaint and a slower telemetry sample,
and the metrics graph sheds anti-aliasing, area fill and curve smoothing under the same signal.

## Added

- **Frame-health measurement while generating** (`renderInterval()`, `strained()` in
  `MainActivity`). The pace is derived from measured frame interval, not text length or a device
  tier - length is a bad proxy for cost (the same reply that stalls a budget phone is nothing to
  a flagship), and a device that never drops a frame runs at the floor interval forever.
  `MetricsGraphView.setStrained()` drops anti-aliasing, fill and smoothing under the same signal
  so the cycles go to decode instead of to the picture of decode.

## Fixed

- **Chat auto-scroll now follows the stream only while the reader is already at the bottom**,
  instead of calling `scrollToPosition` on every repaint - stops fighting anyone scrolled up to
  re-read, and skips a layout pass when the tail is off-screen.
- **Process CPU% is now measured over a minimum 400 ms window** (`CPU_WINDOW_MIN_MS`) instead of
  every call; a call inside that window reuses the last measured value rather than dividing by a
  near-zero interval and reading as a nonsense percentage.
- **Metrics graph now plots samples across the full width of whatever data exists**, instead of
  anchoring to the 120-slot buffer capacity - fixed a spike jammed against the right edge during
  the first minute of a session.
- Replaced the remaining em dashes with plain hyphens across UI strings and error messages
  (`strings.xml`, `InfoActivity`, `BenchmarkActivity`).

## Upgrade notes

- No inference-path, thread-derivation or pinning changes; every published CMF and OPPO benchmark
  result carries over.
- Preferences, conversations, KV session files and imported models carry over in place
  (versionCode 11 -> 12, same signing key): `adb install -r` upgrades without uninstalling.
