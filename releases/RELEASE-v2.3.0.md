# ENTITY v2.3.0 — 2026-07-15

**APK:** `ENTITY-v11-ui-polish-20260715-release.apk` (release)

A visual pass over the whole app plus the small features a daily driver needs. Nothing in the
inference path changed, so every published benchmark number carries over unchanged.

## Added

- **Multi-select conversation delete**: the Conversations dialog gains a Select mode with
  checkboxes and one confirmed bulk delete.
- **Share chat**: export the current conversation as plain text through the system share sheet.
- **Graph style options**: Fill area and Smooth lines for the live metrics graph. Both are
  decorative and follow the Animations setting; with Animations off the graph stays minimal.

## Changed

- **Refined visual system**: hairline borders, neutral assistant bubbles with asymmetric corners,
  a pill-shaped input bar, card-grouped Settings and Benchmark screens, soft green/amber status
  pills for the KleidiAI advisor, consistent 16 dp spacing and a tightened type scale, in both
  light and dark themes. The ENTITY teal accent and the metrics identity are unchanged.
- The toolbar reset action uses a proper reset glyph instead of a pencil.
- The sustained-benchmark 2/5/10 min duration options spread evenly instead of overflowing on
  narrow screens.

## Fixed

- Benchmark CSV meta: `affinity_naive` now reports `mask_all_cores_effectively_unpinned` instead
  of the misleading `pinned_fast_cores`. The naive arm's behavior was always correct - its mask is
  the N fastest of N cores, which is every core - but the label said otherwise.
