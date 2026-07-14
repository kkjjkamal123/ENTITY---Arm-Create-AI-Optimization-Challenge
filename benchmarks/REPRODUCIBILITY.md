# ENTITY benchmark reproducibility and evidence

This page is the compact protocol for verifying ENTITY's Android-app performance claims. It
separates the **shipped app benchmark** from historical Termux/CLI experiments, which have a
different workload and may use CLI-only realtime priority.

## The claim being tested

On a loaded `Llama-3.2-1B-Instruct-Q3_K_L.gguf` model, ENTITY compares:

| Configuration | Exact behavior |
|---|---|
| Naïve | Eight inference threads across all online cores. |
| ENTITY Auto | Native code ranks online CPU cores by `cpuinfo_max_freq`, pins decode to its fastest two to four cores, and uses a wider prompt-processing pool when the runtime API is available. |

The workload is a synthetic llama-bench-style test: **PP 512**, **TG 128**, and one decode token
for the derived TTFT calculation. It measures prompt throughput, decode throughput, battery power,
tokens per watt, start temperature, and derived TTFT. It is not a live multi-turn-chat latency
test.

## Reproduce on a phone

1. Build/install the current app using [BUILD](../docs/BUILD.md), or install the release APK.
2. Import the stated Q3_K_L model. Record its filename and SHA-256 externally if comparing results
   across downloads; model weights are not shipped in this repository.
3. Unplug the phone, leave the screen on, close unnecessary background work, and allow it to cool.
   Power and tok/W are intentionally hidden while the phone is charging.
4. In the app open **⋮ → Benchmark**, choose **3 runs**, and start the test. Do not use the phone
   until it completes.
5. Tap **Export CSV** and retain the file unchanged. Attach it to a pull request or place it under
   [`benchmarks/results/`](results/) when publishing a new device result.

The app runs one discarded PP 64/TG 16 warm-up before measurement. It then runs naïve first and
Auto second. Before every measured pass it pauses at least 15 seconds and, when a battery
temperature is available, waits up to 90 seconds for the battery to return within 0.5°C of the
temperature recorded at benchmark start (without waiting below 37.5°C).

## What an exported CSV proves

The app exports individual pass values—not just the displayed median—for both configurations:

| Category | Included fields |
|---|---|
| Provenance | App version/code, export time, manufacturer/model/fingerprint, Android version/API level, and supported ABIs. |
| Test conditions | Model label, charging state, benchmark-start temperature/thermal status, PP/TG workload, run count, warm-up, order, thread counts, and cooldown policy. |
| Per-pass metrics | Prompt tok/s, decode tok/s, watts, tok/W, derived TTFT, and start temperature. |
| Aggregates | Median and population standard deviation for each metric and configuration. |

The CSV is the preferred evidence artifact. A screenshot makes the outcome easy to inspect; the
CSV makes the individual passes auditable.

## How to interpret the published record

The published results in [BENCHMARKS.md](BENCHMARKS.md) use three passes per configuration and
report median ± population standard deviation. They support the narrower claim that
frequency-ranked fast-core decode improved the measured workload on the two reported phones. They
do **not** prove the same multiplier for every SoC, thermal state, model, quantization, Android
build, or background workload.

The v2.0.0 reference summaries are available in
[device-result-template.csv](device-result-template.csv). Their original per-pass Android CSV
exports were not retained, and no synthetic replacement has been created. The CLI logs in this
directory remain useful historical context only; they must not be compared directly with the
Android-app tables.

## Source audit trail

- Benchmark orchestration, per-pass capture, cooldown, statistics, and CSV export:
  [`BenchmarkActivity.kt`](../app/entity.android/app/src/main/java/com/example/llama/BenchmarkActivity.kt)
- Fast-core ranking, affinity pinning, and split native thread pools:
  [`ai_chat.cpp`](../app/entity.android/lib/src/main/cpp/ai_chat.cpp)
- Rationale and formulas for the runtime policy:
  [OPTIMIZATIONS.md](../docs/OPTIMIZATIONS.md)
- Architecture from UI through JNI to llama.cpp:
  [ARCHITECTURE.md](../docs/ARCHITECTURE.md)

## Contributing a device result

1. Keep the exported CSV as the primary artifact; do not transcribe only its summary.
2. Add one summary row to [device-result-template.csv](device-result-template.csv) and point
   `raw_csv_path` at the retained CSV.
3. State the model filename/hash, app version, device/SoC, Android version, selected backend if
   known, charging state, and any background workload.
4. Do not relabel a Termux/CLI run as an Android-app benchmark, and do not include realtime
   priority in a result claimed for the app.
