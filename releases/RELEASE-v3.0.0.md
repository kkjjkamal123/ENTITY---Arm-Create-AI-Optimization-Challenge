# ENTITY v3.0.0 — 2026-07-18

**APK:** `ENTITY-v13-mono-ui-refresh-20260718-release.apk` (release)

**MONO: full UI remake in the ENTITY Bench design language.** Every screen rebuilt from scratch
around the bench app's two-color system: paper and ink only (white/black, inverted in dark mode),
square corners, monospace type, uppercase section labels, and press feedback as a hard color
inversion instead of ripples. Major version because the interface is a remake — the toolbar and its
overflow menu are gone. No inference-path changes, so every published benchmark number carries over.

## Added

- **Left navigation drawer** replaces the toolbar menu entirely: NEW CHAT and the conversation
  list in one section (tap to switch, long-press to rename/delete, EDIT for multi-select delete;
  the active conversation renders inverted), plus MODEL (switch, model info), TOOLS (benchmark,
  share chat) and SETTINGS / ABOUT.
- **Settings rebuilt into six sections**, absorbing everything from the old overflow menu:
  Theme (System/Light/Dark segments), Interface (chat text size S/M/L, animations, app icon),
  Live metrics (stats bar, graph, graph style, all seven series toggles), Chat (system prompt,
  haptic feedback, keep screen on while generating), Inference (auto/manual sliders, efficiency
  mode, device optimization), Data (manage/delete imported models with size totals, export all
  chats, clear all conversations). About stays outside Settings, in the drawer.
- **Daily-driver features**: chat text size, haptic ticks on send/finish, keep-screen-on during
  generation, model storage management, full-chat export, clear-all.
- The model name lives in the header — tap the title block to switch models without opening the
  drawer; live °C and free-RAM readouts stay beside it.

## Changed

- **Two colors total**: `mono_bg`/`mono_fg`, exactly like ENTITY Bench; theme applied before any
  activity inflates so there is no wrong-mode flash.
- **Chat**: user messages are solid ink blocks, assistant messages bordered boxes; bubbles cap at
  84% of the list width on any device. Markdown inline code renders as reverse video; fenced code
  blocks get a hard left ink bar.
- **Metrics graph**: deliberately the one colored surface in the mono UI — seven overlaid series
  need hue to stay readable, so the data keeps its per-series colors while the chrome around it
  is ink.
- **Dialogs**: square, bordered, monospace, with bold ink action buttons styled explicitly at the
  theme level.
- **Benchmark screen** restyled to bench boxes and segmented pickers; results table scrolls
  horizontally with fixed columns. Measurement logic and CSV schema untouched.

## Removed

- The toolbar, its overflow menu and every menu toggle (relocated into Settings), the teal
  accent, all grays, rounded corners and ripples.

## Upgrade notes

- Preferences, conversations, KV session files and imported models all carry over in place
  (versionCode 8 → 9, same signing key): `adb install -r` upgrades without uninstalling.
