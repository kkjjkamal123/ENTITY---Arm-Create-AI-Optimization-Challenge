# OPTIMIZATIONS

[Home](../README.md) · [Benchmarks](../benchmarks/BENCHMARKS.md) · [FAQ](FAQ.md) · [Contributing](CONTRIBUTING.md) · [License](../LICENSE)

A developer-level deep dive on every Arm-specific optimization ENTITY ships, each pointing at the
exact file and function that implements it. The current end-to-end app result is in
[`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md). This document distinguishes shipped
behavior from historical CLI-only exploration — see §5 in particular.

## 0. Mathematical runtime model

This section states the complete Auto policy in mathematical form. It describes the app's current
implementation rather than an idealized scheduler.

### Symbols

`N` is the number of online CPU cores. `f_i` is the advertised maximum frequency of core `i`.
`M` is the GGUF file size in decimal GB and `F` is free RAM in GiB. `e` is 1 when Efficiency mode
is enabled and 0 otherwise.

### Frequency-ranked core selection

ENTITY reads `cpuinfo_max_freq` for every online CPU core. Let the index ordering `π` sort those
frequencies from highest to lowest:

$$
f_{\pi_0} \ge f_{\pi_1} \ge \cdots \ge f_{\pi_{N-1}}
$$

The Auto decode width reserves two cores when possible, then clamps the result to the supported
fast-core range:

$$
T_{\mathrm{gen}} = \min\left(4,\max\left(2,N-2\right)\right)
$$

$$
S_{\mathrm{gen}} = \{\pi_0,\pi_1,\ldots,\pi_{T_{\mathrm{gen}}-1}\}
$$

`S_gen` becomes the `sched_setaffinity` CPU mask. **Both** phases use that set:

$$
A_{\mathrm{decode}} = A_{\mathrm{prompt}} = S_{\mathrm{gen}}
$$

Until v2.1.0 prompt processing was widened to all `N` online cores, on the assumption that a
compute-bound phase wants all the hardware. Measured, that was a regression: prompt eval on the 4
fast cores runs at 135 tok/s and across all 8 at 86 tok/s, because an A55 is roughly a third of an
A78's throughput and every GEMM waits on the stragglers. The widening was removed. The right width
is an empirical property of the SoC, not a constant, which is why the in-app benchmark decides it.

### Memory-aware context admission

The adaptive context function is:

$$
C(M,F) =
\begin{cases}
8192, & M < 1.6 \land F > 3.0 \\
4096, & M < 1.6 \land F \le 3.0 \\
4096, & M \ge 1.6 \land F > 2.2 \\
2048, & M \ge 1.6 \land F \le 2.2.
\end{cases}
$$

The smaller context is a deliberate admission decision: it reduces KV-cache demand before a
3B-class model uses the remaining memory. In manual mode, the selected context bypasses this
function.

### Thermal policy

Let `s` be Android's thermal status. The base cooperative delay is:

$$
d_0(s) =
\begin{cases}
0, & s \in \{\mathrm{NONE},\mathrm{LIGHT}\} \\
6, & s = \mathrm{MODERATE} \\
12, & s \ge \mathrm{SEVERE}.
\end{cases}
\quad \mathrm{ms}
$$

Efficiency mode doubles it:

$$
d(s,e) = (1+e)d_0(s)
$$

The check follows an eight-token cadence and the thermal status is cached for one second. This is
a cooperative back-off, not a realtime scheduling request or a root-only thermal control.

### Power and benchmark statistics

For raw battery current `I` and battery voltage `V` in millivolts, ENTITY evaluates both possible
OEM unit interpretations:

$$
P_{\mu\mathrm{A}} = \frac{|I|V}{10^9}, \qquad
P_{\mathrm{mA}} = \frac{|I|\times1000\times V}{10^9}
$$

It selects the documented microamp interpretation when it lies in the plausible 0.05 to 15 W
phone range. Otherwise it selects the milliamp interpretation when that is plausible. If neither
is plausible, it falls back to the documented microamp interpretation:

$$
P =
\begin{cases}
P_{\mu\mathrm{A}}, & 0.05 \le P_{\mu\mathrm{A}} \le 15 \\
P_{\mathrm{mA}}, & P_{\mu\mathrm{A}} \notin [0.05,15] \land 0.05 \le P_{\mathrm{mA}} \le 15 \\
P_{\mu\mathrm{A}}, & \text{otherwise}.
\end{cases}
$$

Energy efficiency is:

$$
\eta = \frac{r_{\mathrm{decode}}}{P}
$$

For PP prompt tokens, one decode token `PL`, prompt rate `r_{\mathrm{pp}}`, and decode rate
`r_{\mathrm{tg}}`, the benchmark-derived TTFT estimate is:

$$
\widehat{\mathrm{TTFT}} = 1000\left(\frac{\mathrm{PP}}{r_{\mathrm{pp}}} + \frac{\mathrm{PL}}{r_{\mathrm{tg}}}\right) \ \mathrm{ms}
$$

For an odd number of valid benchmark passes, let `x_(i)` be the sorted values. ENTITY reports:

$$
\widetilde{x} = x_{(n+1)/2}
$$

$$
\sigma = \sqrt{\frac{1}{n}\sum_{i=1}^{n}(x_i-\bar{x})^2}
$$

The current published result uses three passes per configuration. The benchmark is a controlled
throughput and energy comparison, not a claim about live multi-turn-chat latency.

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

**Grounded impact:** the current screenshot-backed in-app run
([`BENCHMARKS.md`](../benchmarks/BENCHMARKS.md)) measures the app-equivalent
comparison directly: **8.0 ± 1.1 → 17.7 ± 0.56 tok/s decode (+121%)** for naïve eight-core
execution versus four big cores on the CMF Phone 1, and **+117%** on a Qualcomm Snapdragon 6 Gen 4
device. Historical CLI output is retained separately in `benchmarks/termux_master_results.txt`.

**What that number does not say — and the ablation that settled it.** Those two arms change *two*
things at once: the thread count drops from 8 to 4, and the surviving threads get pinned. So +121%
is the gain of the shipped configuration over the out-of-the-box default, not a measurement of what
affinity contributes.

Separating them needs a third arm holding the thread count at 4 with affinity off. That arm ships:
`g_pin_cores` (set from Kotlin through `configure()`) makes `init_context()` skip
`build_fast_cpu_set`/`pin_to_fast_cores`, makes `new_threadpool_on_fast_cores()` return null so
llama.cpp falls back to default thread scheduling, and calls `unpin_all_cores()` to clear any mask
inherited from the previous arm. Each arm then logs the mask the kernel actually applied, so a
failed `sched_setaffinity` cannot masquerade as "pinning earns nothing".

**Result, twelve runs across two models on the reference device: the thread count earns +81% to
+106% of decode — roughly 2× — and the pinning earns approximately 0%.**

| Model | Naïve (8 thr) | Threads-only (4 thr, no pin) | Auto (4 thr, pinned) | Pinning earns |
|---|---:|---:|---:|---:|
| 1B Q3_K_L (3 runs) | 8.8 | 16.9 | 16.7 | **−1%** |
| 1B Q4_0 | 7.9 | 14.7 | 14.7 | **+0%** |
| 1B Q4_0 (3 runs) | 7.7 | 15.9 | 16.0 | +1% |
| 1B Q4_0 (3 runs, repeat) | 8.6 | 15.9 | 15.9 | **+0%** |
| 3B Q4_0 | 3.1 | 6.0 | 6.8 | +13% |
| 3B Q4_0 | 3.5 | 6.3 | 6.3 | **+0%** |

The two 3B runs disagree, a third measured −16% while charging, and single 3B runs swing about ±15%,
so the +13% is noise rather than a finding.

**This section's optimization does not earn its headline.** Running eight threads on a 4+4 phone
lets the A55s gate every decode step; simply using four threads removes that, and it is what any
`llama.cpp -t 4` user already gets. The affinity code still ships — it is free, and another SoC may
answer differently — but ENTITY no longer credits it with the speed-up.

The *mechanism* remains SoC-agnostic and is proven so: ranking cores by `cpufreq` rather than
hardcoding a mask finds the performance cluster unchanged across a MediaTek and a Qualcomm layout.
What is disproven is the attribution, not the portability.

## 2. Universal Arm Support via Runtime CPU Backend Dispatch

**Files:** `lib/build.gradle.kts` (`GGML_CPU_ALL_VARIANTS=ON`, `abiFilters = ["arm64-v8a"]`),
`lib/src/main/cpp/CMakeLists.txt` (`GGML_CPU_KLEIDIAI` gated on `ANDROID_ABI STREQUAL "arm64-v8a"`).

v1.x compiled a single backend for a known SoC. v2.0.0 reverses this: the build now compiles **7 Arm
CPU backend variants** (armv8.0, armv8.2×2, armv8.6, armv9.0, armv9.2×2, each with KleidiAI
micro-kernels) via `GGML_CPU_ALL_VARIANTS=ON`. At startup, `ggml_backend_load_all_from_path`
(in `ai_chat.cpp`'s `init()`) loads every variant present in the APK; ggml scores them against the
physical CPU and `prepare()` selects the best one. This makes ENTITY run on essentially any Arm
Android phone: old phones without dotprod get the armv8.0 fallback (no SIGILL), new phones with
i8mm/SVE2 run the optimized variants for those ISAs. Still `abiFilters += listOf("arm64-v8a")` (x86
dropped).

**Performance boundary:** The reference device (Dimensity 7300, armv8.2-a+dotprod) selects the
armv8.2 variant with KleidiAI. The shipped in-app benchmark measures the end-to-end Auto path; it
does not isolate a kernel-only gain. Newer Armv9 cores with i8mm or SME can select stronger
variants when the hardware supports them.

**Trade-off:** APK size increases (~9.8 MB release, up from ~7 MB at v1.7.0) because 7 kernels ship
instead of 1. A custom build could set `GGML_CPU_ALL_VARIANTS=OFF` and target a specific
`GGML_CPU_ARM_ARCH` to go back to a single backend (see [`BUILD.md`](BUILD.md#device-specific-configuration-adapting-ggml_cpu_arm_arch)).

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

## 4. Quantization is what gates Arm's KleidiAI kernels

**Files:** `lib/src/main/java/com/arm/aichat/gguf/FileType.kt` (`kleidiAiAccelerated`),
`MainActivity.buildModelInfo()` (surfaces it on the model-info card).

This is the most valuable Arm-specific finding in the project, and ENTITY shipped for two major
versions without knowing it.

**Arm's KleidiAI registers matmul kernels for exactly two GGML types: `Q4_0` and `Q8_0`.** The
source is unambiguous — `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` gates every kernel on
`GGML_TYPE_Q4_0` / `GGML_TYPE_Q8_0`. Every other type, including the whole K-quant and IQ family,
falls back to generic ggml kernels **regardless of which of the seven CPU backend variants was
loaded at startup**. Shipping an armv8.2+dotprod+KleidiAI backend does nothing for a model the
library has no kernel for.

**Every benchmark ENTITY published before v2.1.0 used `Llama-3.2-1B-Instruct-Q3_K_L`.** KleidiAI
never executed once. The app's model-info card printed "KleidiAI" unconditionally, which told the
user their model was Arm-accelerated when it provably was not.

Measured on the CMF Phone 1 — same phone, same 512-token prompt, same four-thread unpinned
configuration, the *only* difference being the quantization:

| | Q3_K_L (733 MB, generic ggml) | Q4_0 (773 MB, KleidiAI) | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** | **+183%** |
| Derived TTFT (512-token prompt) | 12,050 ms | **4,299 ms** | **−64%** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s | −13% |

The split between the two phases is exactly what the hardware predicts, which is why it is
believable rather than a fluke:

- **Prompt evaluation is a compute-bound GEMM.** It is precisely what KleidiAI's i8mm/dotprod
  kernels exist to accelerate, and it nearly triples.
- **Decode is a memory-bandwidth-bound GEMV.** It tracks bytes-per-weight, not kernel quality. Q4_0
  is ~6% more bytes than Q3_K_L, and it lands ~6-13% slower. A better kernel cannot help a workload
  that is waiting on DRAM.

So the honest guidance is not "Q4_0 is faster" — it is **"Q4_0 is what lets Arm's kernels run at
all, which buys you time-to-first-token, and costs you a little decode."** Q4_0 is also a quality
tradeoff against a K-quant of similar size, so ENTITY **recommends rather than switches**:
`FileType.kleidiAiAccelerated` gates the claim, and the model-info card now says whether the loaded
model can reach KleidiAI and what it costs when it cannot.

## 5. Big-core pinning under contention — app vs. CLI (read this one carefully)

**What the app implements:** affinity pinning only (§1) — `sched_setaffinity`, nothing else.

**What was additionally CLI-benchmarked:** the historical Termux master run used
`llama-cli`/`llama-bench` with
`-Cr 4-7 --cpu-strict 1 --prio 3` — the same affinity pinning **plus** `--prio 3`, which requests
**realtime scheduler priority (`SCHED_RR`)** via `sched_setscheduler`. That flag has no counterpart
in `ai_chat.cpp`; grep it yourself:

```bash
grep -rn "sched_setscheduler\|SCHED_RR" lib/src/main/cpp/
# (no matches)
```

Under a background-download contention scenario, the naive CLI config collapsed to **0.7 tok/s**
(3B model) while the pinned **+ realtime** CLI config held **4.4 tok/s** — a 6.3× gap
(`benchmarks/termux_master_results.txt`). That result demonstrates the
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

**Grounded impact:** the current unplugged in-app run reaches **2.5×** better energy efficiency
(**1.7 ± 0.36 → 4.2 ± 0.23 tok/W**) while drawing less power (**4.7 ± 0.34 → 4.0 ± 0.22 W**)
than naïve eight-core scheduling. See [`BENCHMARKS.md`](../benchmarks/BENCHMARKS.md).

## 7. Thermal-aware guard

**Files:** `app/src/main/java/com/example/llama/ChatViewModel.kt` — `ThermalGuard.delayMs()`,
the cached `thermalStatus()` reader, and the streaming-token collection loop.

In Auto mode, ENTITY consults the guard every eighth generated token. Android thermal status is
cached for one second so this path does not perform a binder call for every token. The policy is:

| Android status | Base delay |
|---|---:|
| NONE or LIGHT | 0 ms |
| MODERATE | 6 ms |
| SEVERE or above | 12 ms |

Efficiency mode doubles the chosen delay. This is a **soft, cooperative** back-off: it does not
change affinity, request realtime scheduling, or read root-only SoC thermal zones. It gives the
operating system a small recovery opportunity before a harder system throttle becomes necessary.

## 8. Prompt-processing / generation thread split

**Files:** `lib/src/main/cpp/ai_chat.cpp` — `init()` (threadpool proc-address resolution),
`prepare()` (threadpool creation + attach), `new_threadpool_on_fast_cores()`.

Before this version, one thread count and one affinity mask covered both prompt processing
(compute-bound, scales with cores) and generation (memory-bandwidth-bound, peaks on a handful of
big cores — see §1). That forced a single tradeoff: pin to only the big cores and prompt
processing gives up the little cores' compute, or widen the mask and generation loses to A55
stragglers.

ENTITY now builds two persistent `ggml_threadpool_t` instances at `prepare()` time: a generation
pool sized and pinned to the big-core count (same core ranking as §1), and a prompt-processing
pool sized to every online core. `llama_attach_threadpool(g_context, g_tp_gen, g_tp_batch)` +
`llama_set_n_threads(g_context, n_gen, n_pp)` tell llama.cpp to switch pools per phase —
generation stays on the 4 Cortex-A78 cores, prompt processing widens to all 8. A manual thread
count from Settings (Auto off) is honored on both phases equally instead of being silently
widened for prompt processing.

**Runtime resolution, not a link-time dependency:** the CPU backend is built with
`GGML_BACKEND_DL` (dynamically loaded — see §2), so `ggml_threadpool_new`/`ggml_threadpool_free`
aren't linkable symbols. `init()` resolves them once via
`ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"/"_free")` against the CPU backend's
registry entry. If either resolves to null, `new_threadpool_on_fast_cores()` returns `nullptr` for
every call, `prepare()` skips `llama_attach_threadpool` entirely, and the context falls back to the
pre-split behavior: a single thread count set via `ctx_params.n_threads`/`n_threads_batch` in
`init_context()`, still pinned via `pin_to_fast_cores()` on every decode entry point.

**Measurement boundary:** the current in-app benchmark uses this shipped split-pool Auto path.
Its prompt and decode figures therefore represent the complete runtime policy rather than an
isolated thread-pool comparison. The project does not claim a separate percentage gain for the
split itself; that would require a controlled pool-on versus pool-off experiment.

## 9. Graceful context-full handling

**Files:** `lib/src/main/cpp/ai_chat.cpp` — `shift_context()`, `decode_tokens_in_batches()`,
`generateNextToken()`.

When a conversation's KV window fills — mid-prompt (a batch about to overflow) or
mid-generation (the running position hits the ceiling) — ENTITY trims the oldest turns instead of
stopping or erroring. `shift_context()` discards the oldest half of the tokens after the system
prompt (`llama_memory_seq_rm` + `llama_memory_seq_add` slide the remainder down) and always
preserves the system-prompt prefix — `system_prompt_position` is the floor for what can be
discarded.

This version fixes position accounting at both call sites:
- **Mid-prompt** (`decode_tokens_in_batches`): triggers when the next batch would push
  `current_position` past `g_n_ctx - OVERFLOW_HEADROOM`. The shift now lands before
  `common_batch_add` assigns positions for the remaining tokens in that batch, so later tokens in
  the same batch aren't decoded at now-stale positions.
- **Mid-generation** (`generateNextToken`): triggers when `current_position` itself hits the
  ceiling before sampling the next token. The fix is `stop_generation_position -=
  shift_context()` — the stop position moves down by exactly the discard count, so a long reply
  neither stops early (stop position pointing past the trimmed KV) nor overruns its `n_predict`
  budget (stop position never catching up to the shifted timeline).

Both fixes matter for the same reason: `shift_context()`'s return value (the discard count) is
what keeps `current_position` and everything derived from it (`stop_generation_position`) in the
same coordinate system after a trim. Before this version, one of the two call sites used a stale
position and could desync silently on a long enough conversation; both paths are now covered.

## 10. JNI robustness / per-token hygiene

**Files:** `lib/src/main/cpp/ai_chat.cpp` — `prepare()`, `setSampler()`, `unload()`,
`primeHistoryNative()`, `generateNextToken()`.

A pass over the JNI boundary closed off several longstanding failure/leak modes:
- **Error paths free what they allocated.** `prepare()` now unwinds on each failure step — a
  failed chat-template init frees the just-allocated `g_batch` and context before returning; a
  failed sampler init additionally resets `g_chat_templates` — so a failed `prepare()` doesn't
  leave a half-initialized context behind.
- **Sampler swap is create-then-swap.** `setSampler()` builds the new sampler before freeing the
  old one, and only swaps `g_sampler` if the new one succeeded — a failed rebuild leaves the
  previous, working sampler in place instead of leaving `g_sampler` null.
- **`unload()` is double-free safe.** Every resource it frees (`g_sampler`, `g_context`,
  `g_tp_gen`/`g_tp_batch`, `g_model`) is reset to `nullptr`/`{}` immediately after freeing, so a
  repeated `unload()` call is a no-op instead of a double-free.
- **Null-context guard on the new entry point.** `primeHistoryNative()` checks `g_context` before
  touching native state, since it — unlike the existing completion-loop functions — can be reached
  from a freshly restored conversation before a model has necessarily finished loading.
- **Per-token Java allocation removed on the partial-UTF8 path.** `generateNextToken()` used to
  allocate a new empty `jstring` every time a token was only a partial multi-byte UTF-8 sequence
  (buffered in `cached_token_chars` for the next call). It now returns a single cached global ref
  (`g_empty_jstring`, created once in `init()`) instead of allocating and immediately discarding a
  `jstring` on every such call.
- **Local-ref hygiene in long histories.** `primeHistoryNative()`'s per-turn loop
  (`GetObjectArrayElement`/`GetStringUTFChars` for each role/text pair) releases both the UTF-8
  chars and the local object refs (`DeleteLocalRef`) before moving to the next turn, so priming a
  long persisted conversation doesn't accumulate local references toward the JNI local-ref table
  limit.

## Summary table

| # | Optimization | Implemented in | Verified via |
|---|---|---|---|
| 1 | Big-core affinity | `ai_chat.cpp: build_fast_cpu_set/pin_to_fast_cores`, ablation switch `g_pin_cores` | three-arm ablation, 6 runs: **earns ~0%**. The thread count earns the gain. |
| 2 | Runtime CPU backend dispatch (7 variants) | `lib/build.gradle.kts (ALL_VARIANTS=ON)`, `CMakeLists.txt` | runtime selection; cross-device validation |
| 3 | Adaptive context | `MainActivity.adaptiveContext`, `ai_chat.cpp: init_context` | manual load test, 3B on 2GB free |
| 4 | Quantization guidance (Q4_0) | model selection + `InfoActivity` | device-specific guidance |
| 5 | Contention ceiling (pinning, +realtime CLI-only) | app: affinity only; CLI: `llama-cli --prio 3` | historical Termux raw output |
| 6 | Energy efficiency metric + power bug fix | `MainActivity.snapMetrics`, `BenchmarkActivity`, `PowerMath` | in-app Benchmark, live stats bar; unit tests |
| 7 | Thermal-aware guard | `ChatViewModel.ThermalGuard` + cached status | unit tests and manual sustained-load test |
| 8 | PP/TG thread split | `ai_chat.cpp: init/prepare/new_threadpool_on_fast_cores` | design-target range; falls back cleanly if unavailable |
| 9 | Graceful context-full trimming | `ai_chat.cpp: shift_context/decode_tokens_in_batches/generateNextToken` | manual long-conversation test (mid-prompt + mid-generation) |
| 10 | JNI robustness / per-token hygiene | `ai_chat.cpp: prepare/setSampler/unload/primeHistoryNative/generateNextToken` | code review; manual long-history test |
| 11 | SoC-neutral UI strings + device detection | `MainActivity`, `InfoActivity`, `DeviceOptimizer` | runtime reporting; 8 unit tests |
