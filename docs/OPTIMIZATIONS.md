# OPTIMIZATIONS

A developer-level deep dive on every Arm-specific optimization ENTITY ships, each pointing at the
exact file and function that implements it. Numbers are cross-referenced against
[`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md). This document is deliberately honest
about what's implemented **in the app** versus what was only demonstrated **from the CLI** — see
§5 in particular.

## 1. Big-core affinity

**Files:** `lib/src/main/cpp/ai_chat.cpp` — `build_fast_cpu_set()`, `pin_to_fast_cores()`.

The Dimensity 7300 is big.LITTLE: 4× Cortex-A78 @2.5 GHz + 4× Cortex-A55 @2.0 GHz. Android's
default CFS scheduler spreads worker threads across all 8 cores; on a memory-bandwidth-bound
decode loop, the slow A55s become stragglers the fast cores end up waiting on.

`build_fast_cpu_set(want)` ranks every online core by reading
`/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`, sorts descending, and takes the top
`want` cores into a `cpu_set_t`. This is **live hardware detection, not a hardcoded core mask** —
it works on any big.LITTLE layout, not just "cores 4–7."

`pin_to_fast_cores()` calls `sched_setaffinity(0, sizeof(cpu_set_t), &g_fast_cpus)` on the calling
thread. It's invoked from three places: `init_context()` (once, after building the context),
`decode_tokens_in_batches()` (every prompt-processing call), and `generateNextToken()` (every
generated token). The repeated calls matter because ggml spawns its worker thread pool lazily from
whichever thread first runs a decode; if a coroutine dispatcher migrates the calling thread, the
pool would otherwise inherit whatever affinity that new thread happened to have.

**This is the only scheduling optimization the app implements.** There is no
`sched_setscheduler`/`SCHED_RR`/realtime-priority call anywhere in `ai_chat.cpp` — see §5.

**Grounded impact:** `benchmarks/BENCHMARKS.md` Experiment 1 (thread/core scaling) shows
generation peaking at 2 threads (17.02 t/s) and *degrading* at 8 threads (8.53 t/s) — using all
cores nearly halves decode speed. The Master Benchmark shows the app-equivalent comparison
(naive 8-core vs. 4-core-pinned) at +34% (1B) and +59% (3B) generation speed.

## 2. Single device-tuned CPU backend

**Files:** `lib/build.gradle.kts` (`GGML_CPU_ALL_VARIANTS=OFF`, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod`,
`abiFilters = ["arm64-v8a"]`), `lib/src/main/cpp/CMakeLists.txt` (`GGML_CPU_KLEIDIAI` gated on
`ANDROID_ABI STREQUAL "arm64-v8a"`).

A stock llama.cpp Android build compiles roughly seven CPU backend variants covering different Arm
feature levels, and dispatches to the right one at runtime (`ggml_backend_load_all_from_path` in
`ai_chat.cpp`'s `init()`). ENTITY instead compiles **exactly one** backend —
`armv8.2-a+dotprod` with KleidiAI micro-kernels — because the target SoC's core is fixed and known.
Combined with `abiFilters += listOf("arm64-v8a")` (x86_64 dropped), this shrinks the APK, cuts
startup RAM (fewer backends resident), and removes the runtime variant-selection indirection.

The Cortex-A78 has the `SDOT` (`dotprod`) int8 instruction but lacks `i8mm` and `SME`. llama.cpp's
default aarch64 kernels already use `dotprod`, so KleidiAI's contribution here is real but modest:
`benchmarks/BENCHMARKS.md` Experiment 2 measures **~+9% prompt-processing** at 4 threads and ~0%
generation gain from `GGML_CPU_KLEIDIAI=ON` vs `OFF`. KleidiAI's larger wins target `i8mm`/`SME`
kernels on newer Armv9 cores this chip doesn't have — worth revisiting on a different target SoC
(see [`BUILD.md`](BUILD.md#device-specific-configuration-adapting-ggml_cpu_arm_arch)).

## 3. Adaptive context window

**Files:** `app/src/main/java/com/example/llama/MainActivity.kt` — `adaptiveContext(model)`;
`lib/src/main/cpp/ai_chat.cpp` — `init_context()` (applies the resulting `n_ctx`).

The phone has 6 GB total RAM but only ~1.5–2 GB free at runtime after the OS baseline. A fixed
large context (e.g. always 8192) would OOM a 3B model; a fixed small one wastes headroom a 1B
model could use. `adaptiveContext()` buckets on model file size and current free RAM
(`ActivityManager.MemoryInfo.availMem`):

```kotlin
private fun adaptiveContext(model: File): Int {
    val sizeGb = model.length() / 1_000_000_000.0
    val freeGb = availableGb()
    return when {
        sizeGb < 1.6 -> if (freeGb > 3.0) 8192 else 4096   // ~1B class
        else -> if (freeGb > 2.2) 4096 else 2048           // ~3B class
    }
}
```

This only runs when **Auto** is on (`Settings.Values.auto`); manual mode uses the user's chosen
context size verbatim. The chosen value is passed through `engine.applyConfig(ctx, ...)` →
JNI `configure()` → `g_n_ctx`, and `init_context()` additionally reads back
`llama_n_ctx(context)` after allocation in case llama.cpp itself clamped it (e.g. against the
model's trained context length) — `g_n_ctx` always reflects what was **actually** allocated, which
is what bounds the completion loop's overflow checks (`OVERFLOW_HEADROOM`,
`decode_tokens_in_batches`, `shift_context`).

Because llama.cpp memory-maps GGUF weights (paged in on demand, not fully resident), the KV cache —
sized by this adaptive logic — is the actual RAM-pressure lever, not the weights themselves. That's
what lets a 3B model load and run within ~2 GB free.

## 4. Quantization: Q4_0 on dotprod

**Files:** none in this repo directly implement quantization (it's a property of the GGUF file you
load) — this is a *model-selection* recommendation grounded in measurement, documented in
`InfoActivity.kt` ("Why Q4_0 wins on this CPU") and `MainActivity.buildModelInfo()` (surfaces the
loaded model's quantization via `FileType.fromCode`).

`benchmarks/BENCHMARKS.md`'s quantization ladder (Llama 1B, optimized config) measured:

| Quant | Size | Gen t/s |
|---|---:|---:|
| Q8_0 | 1262 MB | 11.2 |
| **Q4_0** | 738 MB | **18.45** |
| Q3_K_L | 700 MB | 18.60 |
| IQ3_M | 628 MB | 12.6 |

Generation is memory-bandwidth bound, so raw byte count matters — but **kernel support matters
more**: IQ3_M is smaller than Q4_0 yet 1.5× slower, because llama.cpp's fast aarch64 `dotprod`
kernels exist for Q4_0's block layout but not for the IQ (importance-matrix) formats, which need a
more expensive dequant path. **Recommendation for this hardware class: prefer Q4_0 over sub-4-bit
IQ formats** — smaller is not automatically faster on Arm CPU inference.

## 5. Big-core pinning under contention — app vs. CLI (read this one carefully)

**What the app implements:** affinity pinning only (§1) — `sched_setaffinity`, nothing else.

**What was additionally CLI-benchmarked:** `benchmarks/BENCHMARKS.md` Experiment 3 and the Master
Benchmark's contention test used `llama-cli`/`llama-bench` in Termux with
`-Cr 4-7 --cpu-strict 1 --prio 3` — the same affinity pinning **plus** `--prio 3`, which requests
**realtime scheduler priority (`SCHED_RR`)** via `sched_setscheduler`. That flag has no counterpart
in `ai_chat.cpp`; grep it yourself:

```bash
grep -rn "sched_setscheduler\|SCHED_RR" lib/src/main/cpp/
# (no matches)
```

Under a background-download contention scenario, the naive CLI config collapsed to **0.7 tok/s**
(3B model) while the pinned **+ realtime** CLI config held **4.4 tok/s** — a 6.3× gap
(`benchmarks/BENCHMARKS.md`, Experiment 6 / Master Benchmark Phase 2). That result demonstrates the
*ceiling* of this class of technique under load; it is **not** a number the shipped app reproduces,
because the app only implements the affinity-pinning half.

**Why the app doesn't also set realtime priority:** `SCHED_RR`/`SCHED_FIFO` from an ordinary
(non-privileged) Android app process is unreliable across OEM skins and Android versions — some
manufacturers restrict `sched_setscheduler` for non-system UIDs, and even where it succeeds, a
runaway realtime thread can starve the rest of the system (including the UI thread and Android's
own housekeeping), which is a much worse failure mode on a phone than on a Termux CLI session
you're actively watching. Treat this as a documented next step, not an oversight — see
[`CONTRIBUTING.md`](CONTRIBUTING.md#good-first-issues--next-steps) if you want to explore it safely
(e.g. gated behind an explicit opt-in, or scoped to a short burst rather than the whole generation).

## 6. Energy efficiency as a first-class metric

**Files:** `app/src/main/java/com/example/llama/MainActivity.kt` — `snapMetrics()`;
`app/src/main/java/com/example/llama/BenchmarkActivity.kt` — `readCurrentUa()`, `readVoltageMv()`,
`runPass()`.

Both the live stats bar/graph and the in-app benchmark compute power the same way, with no root
required:

```kotlin
val watts = abs(currentUa.toDouble()) * voltageMv / 1_000_000_000.0   // |µA| × mV / 1e9 = W
```

`currentUa` comes from `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`; `voltageMv` from the sticky
`ACTION_BATTERY_CHANGED` intent's `EXTRA_VOLTAGE`. In the chat view this is sampled once per render
tick (throttled — see `MainActivity.renderAssistant`) alongside temperature and free RAM; in the
benchmark it's sampled every 150 ms during each pass and averaged, then divided into the pass's
measured tokens/sec to get tokens/watt. `BenchmarkActivity` additionally detects charging state
(`isCharging()`) and hides power/efficiency numbers when plugged in, since input current makes the
reading meaningless.

**Grounded impact:** `benchmarks/BENCHMARKS.md` Master Benchmark Phase 2 shows the optimized
(pinned) configuration reaching **2.0×** (1B: 1.98→4.04 tok/s/W) and **7.6×** (3B: 0.16→1.21
tok/s/W) better energy efficiency than naive scheduling, at *equal or lower* absolute power draw —
concentrating work on fewer, faster cores is more efficient than spreading it across all eight.

## 7. Thermal-aware guard

**Files:** `app/src/main/java/com/example/llama/MainActivity.kt` — `isHot()`, the
`THERMAL_DELAY_MS` check in `handleUserInput()`'s collection loop.

```kotlin
if (v.auto && (tokenCount and 7) == 0 && isHot()) delay(THERMAL_DELAY_MS)
```

Every 8 tokens (Auto mode only), the app checks `PowerManager.currentThermalStatus` against
`PowerManager.THERMAL_STATUS_SEVERE`. If the OS itself reports severe thermal pressure, ENTITY
inserts a small delay (`THERMAL_DELAY_MS = 12`ms) between tokens rather than hammering the CPU at
full rate into a hard OS-level throttle. This is a **soft, cooperative** back-off — it doesn't
touch scheduling or affinity, and it relies entirely on Android's own thermal status API rather
than reading SoC thermal zones directly (which aren't root-readable on this device; see
`benchmarks/BENCHMARKS.md`'s time-series experiment). It's deliberately conservative: it only
engages under `THERMAL_STATUS_SEVERE` (not the lighter `MODERATE`/`LIGHT` levels), so normal
sustained chat is unaffected.

## Summary table

| # | Optimization | Implemented in | Verified via |
|---|---|---|---|
| 1 | Big-core affinity | `ai_chat.cpp: build_fast_cpu_set/pin_to_fast_cores` | in-app Benchmark; BENCHMARKS.md Exp. 1 |
| 2 | Single device-tuned backend | `lib/build.gradle.kts`, `CMakeLists.txt` | BENCHMARKS.md Exp. 2 |
| 3 | Adaptive context | `MainActivity.adaptiveContext`, `ai_chat.cpp: init_context` | manual load test, 3B on 2GB free |
| 4 | Quantization guidance (Q4_0) | model selection + `InfoActivity` | BENCHMARKS.md quant ladder |
| 5 | Contention ceiling (pinning, +realtime CLI-only) | app: affinity only; CLI: `llama-cli --prio 3` | BENCHMARKS.md Exp. 3/6 |
| 6 | Energy efficiency metric | `MainActivity.snapMetrics`, `BenchmarkActivity` | in-app Benchmark, live stats bar |
| 7 | Thermal-aware guard | `MainActivity.isHot` + delay | manual sustained-load test |
