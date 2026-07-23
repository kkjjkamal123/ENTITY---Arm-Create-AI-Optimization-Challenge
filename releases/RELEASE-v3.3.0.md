# ENTITY v3.3.0 - 2026-07-22

**APK:** superseded by `ENTITY-v19-models-screen-colour-20260722-release.apk` (v3.4.0)

**Models get a screen instead of a dialog.** v3.2.0 put the catalog in the model picker, and
the picker was an alert dialog with a list of strings. Every entry became four or five lines
of wrapped prose - name, fit tag, size, and the reason it fits - stacked with no separation
between one model and the next. The information was right and unreadable. This release moves
all of it onto a real screen where each fact lands in the same place on every card, so the
list can be scanned rather than read.

## Added

- **A Models screen** (`ModelsActivity`), reached from the drawer's MODEL row and from the
  header. Two sections: **On this phone**, with a per-model card and a storage summary, and
  **Available to download**, with the catalog ranked best-fit-first for the device.
- **One card layout for both**, so an installed model and a catalog entry read as the same
  structure: name, then a fixed facts line (`1.24B · Q4_0 · 773 MB`), then pills, then the
  reason, then actions. Nothing moves between cards.
- **Facts as pills rather than prose.** KleidiAI reach is a filled pill when the
  quantization actually reaches Arm's kernels and a dashed outline when it does not, matching
  the convention the Bench app already uses. Fit is a separate dashed pill.
- **One solid emphasis per card.** Solid inversion is this design's strongest mark, so a card
  gets at most one: `ACTIVE` on an installed model, `RECOMMENDED` on the single best catalog
  entry, in the same top-right position.
- **Actions on the card**: LOAD / DELETE for installed models, DOWNLOAD or RESUME for catalog
  entries, with download progress on a real progress bar and a stop button.

## Changed

- **A downloaded catalog entry is no longer listed twice.** It has a card under *On this
  phone*, so it is dropped from the catalog list rather than appearing in both with two
  different actions.
- Importing from storage moved onto the Models screen alongside downloading; the engine stays
  in the chat screen, so choosing a model returns it as an activity result and the chat loads
  it.

## Upgrade notes

- No inference-path, thread-derivation or pinning changes, so every published benchmark
  number stands.
- versionCode 14 -> 15, same signing key.
