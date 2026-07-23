# ENTITY v3.2.0 - 2026-07-22

**APK:** `ENTITY-v18-model-catalog-20260722-release.apk` (release)

**A fresh install had nothing to talk to.** ENTITY has always required you to supply your own
`.gguf`: find a model host in a browser, judge a quantization and a parameter count against your
phone by eye, download it, then import it through the file picker. That is a reasonable ask of a
developer and an unreasonable one of everyone else, and it is the single step where a first run
most often ended. The model picker now offers a small curated catalog that does the judging on the
device, downloads the file, and loads it when it finishes. Importing from storage is unchanged.

## Added

- **Model catalog in the picker** (`ModelCatalog.kt`). Seven entries across Qwen2.5 0.5B/1.5B and
  Llama 3.2 1B/3B, leaning Q4_0 and Q8_0 because those are the two types Arm's KleidiAI kernels
  accelerate. One K-quant is included on purpose, and its row says plainly that it misses KleidiAI
  and falls back to ggml's Arm repack kernels.
- **Device-aware fit assessment.** Each row is tagged RECOMMENDED / GOOD FIT / FITS / TIGHT /
  TOO BIG for the phone in hand, with a one-line reason naming the quantization and the ISA it
  will actually reach on this CPU - read from the backend variant ggml dlopened for this device,
  so the row describes the kernels that will really run. Fit is computed against **total** RAM,
  not free RAM, so the same phone does not give a different verdict minute to minute.
- **Resumable, cancellable download** (`ModelDownloader.kt`). Bytes land in a `.part` file and a
  retry continues with an HTTP `Range` request instead of starting over. The file only takes its
  real `.gguf` name once its length matches the catalog's expected size, so a truncated download
  can never be mistaken for a loadable model, and a half-finished file never appears in the picker
  or in Settings → Models. Progress reuses the existing load bar; a transfer can be sent to the
  background or stopped.
- **The finished model loads straight into the conversation**, so downloading is one flow rather
  than download-then-go-find-it.

## Changed

- **The empty-state dialog leads with Download.** A phone with no models cannot import one, so
  the first-run dialog now offers Download first, Import second.
- The app now declares `INTERNET` and `ACCESS_NETWORK_STATE`. They are used only by the catalog,
  and only when you tap a model to download it. Inference, prompt processing, generation, chat
  storage and runtime metrics never touch the network, and ENTITY still runs with no connection at
  all once a model is present.

## Upgrade notes

- No inference-path, thread-derivation or pinning changes, so every published benchmark number
  stands and v3.1.0 exports remain comparable.
- versionCode 13 -> 14, same signing key: `adb install -r` upgrades without uninstalling.
