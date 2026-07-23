# ENTITY v3.4.1 - 2026-07-22

**APK:** `ENTITY-v20-edge-insets-20260722-release.apk` (release)

**Content was running underneath the system bars and hard against the edge of the screen.** Every
layout carried `android:fitsSystemWindows="true"`, and it was doing nothing: from targetSdk 35
Android draws applications edge-to-edge and stops honouring that attribute on ordinary containers -
it only ever worked on a handful of inset-aware layouts such as `DrawerLayout` and
`CoordinatorLayout`. ENTITY's screens are built from plain `LinearLayout`s, so they received no
inset padding at all. Rules ran the full width of the display and text sat against the rounded
corners.

## Fixed

- **Real window-inset handling on every screen** (`Insets.kt`). Padding is derived from the system
  bars *and* the display cutout, so nothing sits beneath the status bar, the navigation bar or a
  camera cutout.
- **Insets add to a layout's own padding instead of replacing it**, so each screen keeps the
  gutter it declared rather than losing it the moment insets arrive.
- **Padding is applied to containers, not to children**, so scrolling lists still scroll *under*
  the bars while their content never comes to rest beneath them.
- **The chat input row rises with the keyboard.** It takes the navigation-bar inset and the IME
  inset directly, rather than relying on window resizing.

## Upgrade notes

- Layout-only. No inference-path, thread-derivation or pinning changes, and no colour changes on
  top of v3.4.0 - published benchmark numbers stand.
- versionCode 16 -> 17, same signing key: `adb install -r` upgrades in place.
