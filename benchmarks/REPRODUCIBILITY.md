# ENTITY benchmark reproducibility and evidence

This page is the compact protocol for verifying ENTITY's Android-app performance claims. It
separates the **shipped app benchmark** from historical Termux/CLI experiments, which have a
different workload and may use CLI-only realtime priority.

## The claim being tested

On a loaded `Llama-3.2-1B-Instruct-Q3_K_L.gguf` model, ENTITY compares:

| Configuration | Exact behavior |
|---|---|
| Naïve | Eight inference threads across all online cores. |
| Threads only | The same thread count Auto derives, with `pinCores` off: no `sched_setaffinity`, no pinned thread pool, placement left to the Linux scheduler. Equivalent in policy to an upstream llama.cpp `-t N` run. |
| ENTITY Auto | Native code ranks online CPU cores by `cpuinfo_max_freq`, pins decode to its fastest two to four cores, and uses a wider prompt-processing pool when the runtime API is available. |

The middle arm exists so the result can be attributed rather than assumed. Naïve versus Auto varies
thread count and core placement together; on its own it cannot say which one produced the gain.
Naïve versus threads-only isolates the thread count, threads-only versus Auto isolates the pinning.
**Decode is the isolated row.** Prompt throughput is not: only Auto widens prompt processing to all
cores, so that row mixes the two effects by construction.

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

### Confirm the ablation actually ran on different cores

The three-arm attribution is only meaningful if the arms really executed on different cores. A
failed `sched_setaffinity` would look identical to "pinning earns nothing", so the native code logs
the mask **the kernel reports back**, per arm. With the phone attached:

```bash
adb logcat -s ai-chat | grep "effective cpus"
```

On an 8-core big.LITTLE phone a valid run prints three distinct lines, in this order:

```
init_context: pinned inference to 8 fast cores, effective cpus [0,1,2,3,4,5,6,7]        <- naive
init_context: affinity off, 4 threads placed by the scheduler, effective cpus [0,...,7] <- threads-only
init_context: pinned inference to 4 fast cores, effective cpus [4,5,6,7]                <- Auto
```

The naïve arm spans every core because a set of the eight fastest cores on an eight-core phone *is*
every core; only its thread count distinguishes it. The exact fast-core indices in the Auto line
depend on the SoC's core numbering, which is why the code ranks `cpuinfo_max_freq` instead of
hardcoding 4-7.

**If threads-only and Auto print the same mask, the ablation did not happen** and its attribution
must not be published. A `sched_setaffinity failed` warning in the same log means the same thing.

The app runs one discarded PP 64/TG 16 warm-up before measurement. It then runs naïve, threads-only
and Auto in that order. Before every measured pass — in every arm, so the ordering does not favour
the last one — it pauses at least 15 seconds and, when a battery temperature is available, waits up
to 90 seconds for the battery to return within 0.5°C of the temperature recorded at benchmark start
(without waiting below 37.5°C).

## What an exported CSV proves

The app exports individual pass values—not just the displayed median—for all three configurations:

| Category | Included fields |
|---|---|
| Provenance | App version/code, export time, manufacturer/model/fingerprint, Android version/API level, and supported ABIs. |
| Test conditions | Model label, charging state, benchmark-start temperature/thermal status, PP/TG workload, run count, warm-up, arm order, per-arm thread counts, per-arm affinity policy (`affinity_naive`, `affinity_threads_only`, `affinity_optimized`), and cooldown policy. |
| Per-pass metrics | Prompt tok/s, decode tok/s, watts, tok/W, derived TTFT, and start temperature. |
| Time series | 150 ms samples of app-process CPU utilization, free RAM, battery temperature, Android thermal state, and battery watts. |
| Aggregates | Median and population standard deviation for each metric and configuration. |

Rows are keyed by config: `naive`, `threads_only`, `optimized`. The attribution is computed from
the three `tg` medians; the app also prints it under the results table.

The CSV is the preferred evidence artifact. A screenshot makes the outcome easy to inspect; the
CSV makes the individual passes auditable.

## Generate the four evidence graphs

Use the retained CSV to create CPU utilization, memory availability, thermal-throttle, and power
consumption graphs:

```bash
python3 -m pip install matplotlib
python3 benchmarks/plot_telemetry.py path/to/entity_bench.csv benchmarks/plots
```

The script writes `cpu_utilization.png`, `memory_usage.png`, `thermal_throttle.png`, and
`power_consumption.png`. CPU is **ENTITY's process CPU percentage** and can exceed 100% when
llama.cpp uses multiple cores; it is not a claim about whole-device CPU utilization. The thermal
plot pairs battery temperature with Android's reported thermal state, so a throttle conclusion is
grounded in the operating system signal rather than temperature alone.

## How to interpret the published record

The published results in [BENCHMARKS.md](BENCHMARKS.md) use three passes per configuration and
report median ± population standard deviation. They support the narrower claim that ENTITY's Auto
configuration improved the measured workload over the eight-thread default on the two reported
phones. They do **not** prove the same multiplier for every SoC, thermal state, model,
quantization, Android build, or background workload.

They also do **not** attribute that gain to core pinning. The published tables are a two-arm record
taken before the threads-only arm existed, so the thread-count decision and the affinity policy are
still confounded in them. The three-arm run separates the two and is
[pending a device](BENCHMARKS.md#pending-the-three-arm-attribution); its `threads_only` columns in
the device-result template are marked `not-measured` rather than back-filled.

The v2.0.0 reference summaries are available in
[device-result-template.csv](device-result-template.csv). Their original per-pass Android CSV
exports were not retained, and no synthetic replacement has been created. The CLI logs in this
directory remain useful historical context only; they must not be compared directly with the
Android-app tables.

## Source audit trail

- Benchmark orchestration, three-arm ablation, per-pass capture, cooldown, statistics, attribution,
  and CSV export:
  [`BenchmarkActivity.kt`](../app/entity.android/app/src/main/java/com/example/llama/BenchmarkActivity.kt)
- Fast-core ranking, affinity pinning, split native thread pools, and the `pinCores` switch that
  the threads-only arm turns off:
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
