# ENTITY Bench

A standalone, installable version of ENTITY's benchmark. It runs the three-arm ablation
from the main app - and nothing else. There is no chat: you import a GGUF model, run the
ablation on your own phone, read the attribution, and export every pass to CSV.

Its purpose is to let anyone with an arm64 Android phone reproduce or challenge ENTITY's
central finding on their own SoC: that on a 4+4 big.LITTLE phone the decode speed-up comes
from the thread count, not from big-core pinning. A different SoC may well answer
differently, which is the point of shipping the experiment rather than an assertion.

## The three arms

The benchmark runs the same synthetic PP 512 / TG 128 workload three ways on the loaded
model, with a thermal cooldown before every pass:

| Arm | Configuration |
|---|---|
| Naive | 8 threads across all online cores. The out-of-the-box default. |
| Threads only | The same thread count Auto derives, with affinity off: no `sched_setaffinity`, no pinned pool, placement left to the Linux scheduler. This is what an upstream `llama.cpp -t N` run does. |
| ENTITY Auto | The shipped path: both phases on the fast-core thread count, pinned to the performance cluster. |

Naive and Auto differ in two variables at once - thread count and core placement - so a
two-arm result cannot say which one earned the gain. The middle arm holds the thread count
at Auto's value and drops only the affinity, so:

- naive to threads-only isolates the thread count
- threads-only to Auto isolates the core pinning

The app prints that split under its own results table. That is why the middle arm exists:
without it, "Auto is 2x faster" cannot be attributed.

## Install

The app is arm64-v8a only and requires Android 13+.

```bash
adb install -r apk/ENTITY-Bench-v1.0.0-release.apk
```

Launch it, tap the model field, and import a runnable GGUF model from device storage (the
app copies it into its own storage; it does not need the chat app). Use the same GGUF you
want to compare - `Llama-3.2-1B-Instruct-Q4_0.gguf` is the reference model in this repo's
records.

## Run a valid benchmark

The numbers are only comparable if the run conditions match the ones in
[`benchmarks/BENCHMARKS.md`](../../benchmarks/BENCHMARKS.md):

1. **Unplug the phone.** Power and tokens-per-watt are hidden while charging by design -
   USB input makes the battery-current reading the charger's, not the workload's. Speed
   numbers stay valid while charging; power does not.
2. **Start cool.** Let the phone sit at rest so the battery is near ambient. The app cools
   back toward the pre-benchmark temperature before every pass, but a hot start still
   biases the first arm.
3. **Choose 3 runs**, not 1. A single pass swings roughly +/-15%; the median of three is
   what the published rows use.
4. **Let the cooldowns finish.** Between passes the app pauses (at least 15 s, up to 90 s)
   until the battery returns to within 0.5 C of its pre-benchmark temperature. Do not
   interrupt it.

The app runs a discarded warm-up, then naive, threads-only and Auto in that order, with the
same cooldown before every pass so the ordering cannot favour the last arm. The results
table shows prompt and decode throughput, derived TTFT, power and tok/W (unplugged only),
per-core clocks, thermal state, and the decode attribution.

A **sustained** test is also available: back-to-back passes for a fixed duration with only a
short gap instead of a cooldown, so heat accumulates. It runs only threads-only and Auto, to
see whether pinning pays off once the little cores have heated up. Read the trend across
passes, not any single one.

## Export a CSV and contribute a row

After a run, tap **Export CSV**. The file carries device fingerprint, app version, model,
charging state, thermal starting point, per-pass values, per-core CPU-frequency samples, and
the CPU mask each arm actually applied - so a failed pin cannot pass as "pinning earns
nothing". A median/stddev block per arm is included.

To contribute your device's result:

1. Fill one row in
   [`benchmarks/device-result-template.csv`](../../benchmarks/device-result-template.csv),
   matching its header (app version, SoC, model, quantization, charging state, thermal
   start, and the per-arm speed/power columns).
2. Commit your raw exported CSV beside it and reference it in the `raw_csv_path` column.

See the "Contribute a device result" section of
[`benchmarks/BENCHMARKS.md`](../../benchmarks/BENCHMARKS.md) for the full convention. Keep
the model, quantization, app version, backend, thermal start and charging state with the
row; nothing is back-filled from another run.

## Build from source

Like the main app, ENTITY Bench cannot build standalone: its native `CMakeLists.txt` does
`add_subdirectory()` six directory levels up, so the app must sit inside a llama.cpp checkout
at `examples/entity.bench.android/`.

```bash
curl -sL -o llama.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
tar xzf llama.tar.gz                                          # -> llama.cpp-master/
cp -r app/entity.bench.android llama.cpp-master/examples/entity.bench.android
cd llama.cpp-master/examples/entity.bench.android
./gradlew :app:assembleRelease --no-daemon --console=plain
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`. Without a
`keystore.properties`, the release build falls back to the debug signing key. The toolchain
(JDK 17, NDK 27.1.12297006, CMake 3.31.6, Android SDK 36) is the same one documented in
[`docs/BUILD.md`](../../docs/BUILD.md); the first build compiles llama.cpp itself and takes
several minutes.
