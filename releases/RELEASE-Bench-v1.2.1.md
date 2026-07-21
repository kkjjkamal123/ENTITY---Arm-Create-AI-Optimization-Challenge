# ENTITY Bench v1.2.1 - 2026-07-21

**APK:** `ENTITY-Bench-v1.2.1-release.apk` (release)

**Interface pass, no measurement changes.** Everything in this release is UI: the bench app is now
visually identical to the chat app where the two share a design vocabulary, and three rough edges
found in a review are gone. The measurement core, the arms, the sweep and the CSV schema are
untouched, so every result exported by v1.2.0 remains comparable.

## Fixed

- **The two apps now share one corner radius.** Every MONO surface in the bench app - boxes,
  buttons, dialogs, segmented pickers, the bar tracks - was still hard-square while the chat app
  moved to a single 10 dp radius in v3.0.2. They read as one design family again; the radius is
  one `@dimen/mono_radius` value, as it is in the chat app.
- **"NO KLEIDIAI" no longer renders as a solid inverted pill.** Solid inversion is this design's
  strongest emphasis, so painting the negative state that way made a model that cannot reach Arm's
  kernels look endorsed. The accelerated case keeps the solid pill; the unaccelerated case is now a
  dashed outline, matching how the chat app's model info card already distinguished the two.
- **Importing your first model now explains itself.** With nothing imported, the picker showed a
  single "Import from device…" row and no context. It now shows the same titled dialog the chat app
  does, so a cold open on a fresh phone says what a .gguf is and that the app copies it in.
- **The home screen no longer ends after the config card on a first run.** With no saved results,
  a short dashed note says so instead of two cards silently vanishing.
- **Back buttons meet the 48 dp touch-target minimum** and carry a content description, so they no
  longer announce as "less than" to TalkBack.
- Removed a hardcoded `v1.1.0` from the footer string, which was stale and overwritten from
  `BuildConfig` at runtime anyway, and added the `textColorHint` the chat app's theme sets.

## Upgrade notes

- No measurement, arm, sweep or CSV-schema changes: v1.2.0 exports and v1.2.1 exports are directly
  comparable, and saved results carry over.
- versionCode 4 -> 5, same signing key: `adb install -r` upgrades without uninstalling.
