# ENTITY v1.2.0 — 2026-07-03

**APK:** `ENTITY-v2-modelinfo-progress-20260703-2048.apk`

A release about getting a model into the app on *any* phone, not just the developer's. The previous
builds expected you to copy a `.gguf` file into the app's `Android/data/…` folder, which works over adb
but is silently blocked for normal file managers on modern Android's scoped storage — so on a second
phone, loading a model was effectively impossible. This version replaces that with a proper in-app
**Import from device** flow built on the Storage Access Framework: you pick the file from anywhere in
your storage and ENTITY copies it in for you, with a real progress percentage while it does. Imported
models now keep their actual filename instead of an `imported-<timestamp>` placeholder, and a new model
info card reads the GGUF header to show exactly what you loaded — parameters, quantization, architecture,
trained vs running context, and the CPU compute path. A dead-end empty-state dialog that offered no way
to import was also fixed.

## Added
- **Loading progress bar** — a real percentage while importing a picked file, then an indeterminate bar
  while the engine loads, so large models don't look frozen.
- **Model info card** (⋮ → Model info) — reads the GGUF header and shows parameters, quantization,
  architecture, trained vs running context, layers, embedding, vocab, and the CPU compute path.

## Fixed
- **Model loading failed on other phones** — the app told users to copy the file into
  `Android/data/…`, which scoped storage blocks on modern Android. Models are now added via an in-app
  **Import from device** picker (Storage Access Framework) that copies the file in for you.
- **Imported models kept a junk name** — they now keep their real filename instead of
  `imported-<timestamp>`.
- **Empty picker was a dead end** — with no models, the dialog showed a message and no way to act; it
  now shows a real **Import from device…** button (an Android dialog can't show a message *and* a list).
