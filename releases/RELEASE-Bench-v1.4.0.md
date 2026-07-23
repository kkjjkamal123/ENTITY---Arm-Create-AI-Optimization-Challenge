# ENTITY Bench v1.4.0 - 2026-07-22

**APK:** `ENTITY-Bench-v1.4.0-release.apk` (release)

**A benchmark app is useless without a model, and getting one used to be your problem.** Until now
the only way to put a `.gguf` on the phone was to find a model host in a browser, judge a
quantization and a parameter count against your device by eye, download it, and import it through
the file picker. This release adds a small curated catalog that does the judging on the device and
downloads the file for you. Importing from storage still works exactly as before.

## Added

- **Model catalog** (`ModelCatalog.kt`). Seven entries across Qwen2.5 0.5B/1.5B and Llama 3.2
  1B/3B, leaning Q4_0 and Q8_0 because those are the two types Arm's KleidiAI kernels accelerate.
  One K-quant is included on purpose, and its row says plainly that it misses KleidiAI.
- **Device-aware fit assessment.** Each row is tagged RECOMMENDED / GOOD FIT / FITS / TIGHT /
  TOO BIG for the phone in hand and carries a one-line reason naming the quantization and the ISA
  it will actually reach on this CPU. The judgement is made *before* a multi-GB download rather
  than discovered after it. Fit is computed against **total** RAM, not free RAM, so the same phone
  does not give a different verdict minute to minute depending on what else is running.
- **Resumable, cancellable download** (`ModelDownloader.kt`). Bytes land in a `.part` file and a
  retry continues with an HTTP `Range` request instead of starting over. The file only takes its
  real `.gguf` name once its length matches the catalog's expected size, so a truncated download
  can never be mistaken for a loadable model, and a half-finished file never appears in the model
  picker. A running transfer can be sent to the background or stopped.
- Unit cover for the catalog shape, the fit rules and the recommendation in `ModelCatalogTest`.

## Changed

- The app now declares `INTERNET` and `ACCESS_NETWORK_STATE`. They are used only by the catalog,
  and only when you tap a model to download it. Benchmarking, measurement, saved results and CSV
  export never touch the network.

## Upgrade notes

- No measurement, arm, sweep or CSV-schema changes: v1.3.0 exports and v1.4.0 exports are directly
  comparable, and saved results carry over.
- versionCode 6 -> 7, same signing key: `adb install -r` upgrades without uninstalling.
