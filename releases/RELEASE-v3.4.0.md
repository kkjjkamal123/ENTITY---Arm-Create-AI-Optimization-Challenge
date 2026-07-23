# ENTITY v3.4.0 - 2026-07-22

**APK:** `ENTITY-v19-models-screen-colour-20260722-release.apk` (release)

**MONO was too bright to use for long, and that was a measurable fault rather than a matter
of taste.** Every previous version painted pure `#FFFFFF` on pure `#000000` - a 21:1 contrast
ratio. On a black field the iris opens wider and white glyphs bleed into their own edges, an
effect called halation; it reads as glare within minutes and is worst for the roughly half of
people with some astigmatism. Material's own dark-theme guidance is never to use black as the
base surface for exactly this reason. This release retunes the whole palette, keeps the
monochrome identity, and adds an optional colour palette for people who want one.

## Changed

- **Pure black and pure white are retired.** Dark theme is now `#121212` base, `#1E1E1E`
  cards, `#E4E4E4` text - Material's dark-surface baseline and its 87% high-emphasis text
  level. Light theme is `#F1F0EC` paper with `#F9F8F5` cards and `#1F1F1D` ink. Body contrast
  goes 21:1 -> 15:1, still comfortably above WCAG AAA's 7:1 for body text.
- **Cards are lighter than the page in light theme**, not the other way round. That is
  Carbon's light layering model, which alternates surfaces rather than piling white on white,
  and it lowers emitted light without touching text contrast.
- **Borders stopped shouting.** Every card carried a 2dp border at full text strength; that
  is a great deal of bright line area, and area is what makes an interface glare. Borders are
  now 1dp of a dedicated outline tone.
- **Large filled areas are dimmer than text.** Filled buttons use a `fill` token deliberately
  darker than the text token in dark theme, because a full-width bright slab contributes far
  more perceived glare than a line of type.
- **Secondary text has its own level** rather than being full-strength type at a smaller size.

## Added

- **Palette switch in Settings -> Theme: MONOCHROME or COLOUR.** Monochrome is the default and
  is unchanged in character. Colour keeps identical layout, spacing and luminance and changes
  only hue: lightly tinted surfaces, one accent on the primary action, and separate danger and
  success tones. Semantic tones are deliberately *not* the accent hue - if brand and error
  share a colour, identity cannot be told from warning. Every coloured state still carries
  text or shape saying the same thing, so meaning never depends on colour alone (WCAG 1.4.1).
- **Colour is addressed by role, as theme attributes.** Layouts, drawables and colour state
  lists reference `?attr/monoFg` and friends; raw colour resources now appear only in the two
  palette themes. That indirection is what makes a runtime palette switch possible at all.

## Fixed

- **A model could not be reloaded after reopening the app.** The active-model preference
  persists across restarts but the engine does not, so a freshly opened app showed the last
  model as LOADED on a disabled button with nothing actually loaded, and there was no way to
  load it. "Loaded" is now reported by the chat screen from real engine state. The same stale
  flag also wrongly blocked deleting that model.

## Upgrade notes

- No inference-path, thread-derivation or pinning changes; published benchmark numbers stand.
- versionCode 15 -> 16, same signing key: `adb install -r` upgrades in place.
