# ENTITY Bench v1.5.0 - 2026-07-22

**APK:** `ENTITY-Bench-v1.5.0-release.apk` (release)

**The bench app now looks exactly like the chat app, and finally reports SME2.** Two apps that
share a design language had drifted: the chat app retired pure black and pure white in v3.4.0 for
measured readability reasons, and gained a palette switch and proper edge-to-edge handling, while
the bench app was still painting `#FFFFFF` on `#000000`. This release ports that work across
unchanged. No measurement code was touched.

## Changed

- **Pure black and pure white retired**, on the same evidence as the chat app: `#FFFFFF` on
  `#000000` is a 21:1 contrast ratio and causes halation - white glyphs bleeding into their own
  edges on a black field, worst for the roughly half of people with some astigmatism. Dark is now
  Material's `#121212` surface with `#1E1E1E` cards and `#E4E4E4` text (15:1, still above WCAG
  AAA for body text); light is `#F1F0EC` paper with cards lighter than the page.
- **Borders are 1dp of a dedicated outline tone** rather than 2dp at full text strength, and large
  filled areas are deliberately dimmer than text, because area multiplies perceived glare.
- **Colour is addressed by role, as theme attributes** - the same `attrs.xml` the chat app uses.
  The palette files are copied across byte-identical so the two apps can be diffed.
- **Edge-to-edge is handled properly on every screen.** From targetSdk 35 Android stops honouring
  `android:fitsSystemWindows` on ordinary containers, so content ran under the system bars and text
  sat against the display cutout. Real window-inset padding replaces it.

## Added

- **Palette switch in Settings: MONOCHROME or COLOUR**, directly under the existing theme row.
  Monochrome is the default and unchanged in character; colour keeps identical layout, spacing and
  luminance and varies only hue.
- **SME2 is detected and reported.** `DeviceInfo.cpuFlags()` parsed `sve`, `sve2` and `sme` but
  never `sme2`, which is its own `/proc/cpuinfo` flag, so the lever could not light up on hardware
  that has it. A new `sme2 kleidiai` chip now appears in the optimization grid.

## Notes on SME2

This is a **reporting** fix, not a new capability. The shipped build already includes ggml's
`android_armv9.2_2` variant (`DOTPROD MATMUL_INT8 FP16_VECTOR_ARITHMETIC SVE SVE2 SME`), and
KleidiAI's SME2 microkernels (`kai_matmul_..._sme2_mopa`, `..._sme2_dot`, `..._sme2_sdot`) are
compiled in whenever `+sme` is set. On SME2 silicon those kernels were already running; the app
simply never said so. Seven CPU variants ship in total, covering armv8.0 through armv9.2 with
SVE2 and SME, and ggml selects the strongest one the device supports at runtime - so any arm64
phone from pre-dotprod to SME2 gets the best kernels it can run.

## Upgrade notes

- No measurement, arm, sweep or CSV-schema changes: v1.4.0 and v1.5.0 exports are directly
  comparable, and saved results carry over.
- versionCode 7 -> 8, same signing key: `adb install -r` upgrades without uninstalling.
