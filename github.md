# ENTITY — An Optimized On-Device LLM Runtime for Arm Phones

> A fully offline, adaptive LLM chat app for Android that profiles the device Arm CPU and automatically tunes itself to run any runnable GGUF model as fast as the hardware allows.

**Track: Mobile AI**

---

## Project Overview

ENTITY is a demonstration that mid-range Arm phones can run private, fully offline AI assistants *smoothly* — but only if the software is designed for the silicon instead of treated like a small desktop.

Built on **llama.cpp** with a Kotlin UI and C++/JNI inference layer, ENTITY is proven on the **CMF Phone 1** (MediaTek Dimensity 7300, 6 GB RAM, Android 16). The phone has a big.LITTLE CPU: 4× Cortex-A78 performance cores @2.5 GHz + 4× Cortex-A55 efficiency cores @2.0 GHz. This asymmetry is where naive apps stumble and tuned ones win.

**Why it should win:** ENTITY adds three things competitors don't do.

1. **Universal Arm support, plus the KleidiAI gate nobody talks about** — ships 7 CPU backend
   variants (armv8.0 to armv9.2, each compiled with KleidiAI) and selects the best one at startup:
   no SIGILL on older cores, no missed kernels on newer ones. But shipping the variant is not
   enough, and this is the finding ENTITY is proudest of: **KleidiAI has kernels for Q4_0 and Q8_0
   only.** Load any other quantization and Arm's kernels never execute, whatever backend was
   selected. ENTITY reads the GGUF header and tells you. Switching a 1B from Q3_K_L to Q4_0 took
   prompt throughput from 43 to 121 tok/s on the reference phone.
2. **Measured runtime tuning, not assumed** — ENTITY keeps both inference phases on the
   frequency-ranked performance cores, and ships a three-arm ablation that *attributes* the result
   instead of asserting it. That ablation disproved this project's own flagship optimization: the
   gain comes from the thread count, not from the `sched_setaffinity` pinning. Shipping the
   experiment that can falsify your headline is the point.
3. **Energy efficiency as a first-class metric** — measures battery current × voltage to report power
   in watts and tokens-per-watt, turning on-device AI from a raw-speed story into a sustained-efficiency
   one. A bug fix in v2.0.0 resolved OEM kernel unit confusion (milliamps vs microamps) so power
   reporting is now accurate on all devices.

On the same phone, ENTITY reaches **16.7 tok/s** decode on a 1B model (Q3_K_L, shipped Auto config), and — after the KleidiAI
finding below — **time-to-first-token dropped from 13.4 s to 3.9 s**. It also reports power and
tokens-per-watt. Full method and limits: [`benchmarks/BENCHMARKS.md`](benchmarks/BENCHMARKS.md).

---

## Functionality & Output

The app is a professional on-device chat interface with:
- **In-app model loading** via Android's Storage Access Framework (no manual file-manager
  copying needed).
- **Smooth streaming chat** with Stop and New Chat buttons, Markdown rendering in assistant replies, and long-press Copy/Regenerate.
- **Persistent, multiple conversations** — chats are stored on-device (SQLite), the last conversation is restored on launch, and a conversation switcher supports rename/delete; restored chats continue seamlessly (the engine re-primes its context from the saved history).
- **Settings** with an Auto (optimized) master toggle for automatic tuning, plus manual layers for temperature, top-k, top-p, max tokens, context size, and thread count — and an editable system prompt and an Animations toggle.
- **Live metrics bar and toggleable multi-series graph** — tokens, tok/s, time-to-first-token, temperature, power draw (W), and free memory.
- **In-app benchmark with a three-arm ablation** (⋮ → Benchmark) — runs the same PP 512 / TG 128 workload three ways: naïve (8 threads, default scheduler), threads-only (the Auto thread count with affinity off, i.e. what an upstream llama.cpp `-t N` run does), and the shipped Auto path (both phases on the frequency-ranked fast cores). The middle arm is what lets the gain be *attributed* — thread count versus core pinning — instead of assumed. It has a 1/3/5 run-count selector, per-metric median ± stddev, thermal cooldown between every pass, TTFT, and CSV export.
- **Light / Dark / System themes** and a theme-aware app-icon switcher.
- **Model info card** that reads the GGUF header to show parameters, quantization, architecture, and computed context window.

### Current measured optimization gain

The current screenshot-backed **in-app** run uses `Llama-3.2-1B-Instruct-Q3_K_L`, PP 512 / TG
128, three runs per configuration, and an unplugged CMF Phone 1. It compares naïve eight-thread
execution with the shipped Auto path: four Cortex-A78 cores pinned by `sched_setaffinity`.

This is the gain of the shipped configuration over what the phone does out of the box. It is
**not** an attribution to core pinning, and it is not presented as one — see
[What this number does not say](#what-this-number-does-not-say) below.

| Metric | Naïve (8 cores) | Optimized (4× A78) | Gain |
|--------|:---:|:---:|:---:|
| Prompt throughput | 42.2 ± 0.34 tok/s | 43.2 ± 1.8 tok/s | +2% |
| **Decode throughput** | **8.0 ± 1.1 tok/s** | **17.7 ± 0.56 tok/s** | **+121%** |
| Derived TTFT | 12,245 ± 108 ms | 11,907 ± 452 ms | −3% |
| Power draw | 4.7 ± 0.34 W | 4.0 ± 0.22 W | lower |
| **Energy efficiency** | **1.7 ± 0.36 tok/W** | **4.2 ± 0.23 tok/W** | **2.5× / +148%** |

#### Cross-vendor validation: Snapdragon 6 Gen 4

The same optimization reproduces on Qualcomm silicon: OPPO CPH2729 with Snapdragon 6 Gen 4 (SM6650), 7.4 GB RAM, Android 16. Same model (`Llama-3.2-1B-Instruct-Q3_K_L`), same protocol, same in-app benchmark:

| Metric | Naïve (8 cores) | Optimized (4 perf cores) | Gain |
|--------|:---:|:---:|:---:|
| Prompt throughput | 39.3 ± 2.2 tok/s | 47.7 ± 0.12 tok/s | +21% |
| **Decode throughput** | **6.0 ± 1.1 tok/s** | **13.1 ± 0.05 tok/s** | **+117%** |
| TTFT | 13,194 ± 672 ms | 10,811 ± 28 ms | −18% |
| Power draw | 3.4 ± 0.15 W | 3.4 ± 0.29 W | flat |
| **Energy efficiency** | **1.8 ± 0.24 tok/W** | **3.8 ± 0.31 tok/W** | **2.1× / +114%** |

The core mechanism — ranking CPU cores by their maximum clock frequency from `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` and pinning inference to the performance cluster via `sched_setaffinity` — requires **no vendor-specific code**. The identical code path detects and uses the performance cores on both MediaTek and Qualcomm. On Snapdragon, power remains flat at 3.4 W while decode throughput more than doubles: the efficiency gain comes from finishing work faster at the same power cost, not from consuming additional current.

![Current in-app benchmark](screenshots/Benchmark.png)

### What this number does not say - and what ENTITY's own ablation found

Naive and Auto differ in **two** variables at once: the thread count drops from 8 to 4, and the
surviving threads get pinned to the performance cluster. So +121% is the gain of the shipped
configuration over the out-of-the-box default. It is **not** a measurement of what core pinning
contributes.

ENTITY now ships the arm that separates them: **threads-only**, which runs Auto's thread count with
affinity switched off - exactly what an upstream `llama.cpp -t 4` run does. Six runs across two
models, on the reference device:

| Model | Naive, 8 thr | Threads only, 4 thr no pin | Auto, 4 thr pinned | Thread count | Pinning |
|---|---:|---:|---:|---:|---:|
| 1B Q3_K_L (3 runs) | 8.8 | 16.9 | 16.7 | **+92%** | **-1%** |
| 1B Q4_0 | 7.9 | 14.7 | 14.7 | **+86%** | **+0%** |
| 3B Q4_0 | 3.1 | 6.0 | 6.8 | **+94%** | +13% |
| 3B Q4_0 | 3.5 | 6.3 | 6.3 | **+81%** | **+0%** |

**The thread count earns the gain. The big-core pinning earns approximately nothing.** The two 3B
runs disagree (+13% and +0%), a third measured -16% while charging, and single 3B runs swing about
15% either way, so the +13% is noise rather than a finding.

This is the project's flagship optimization, and its own benchmark disproved it. The affinity code
still ships - it costs nothing and another SoC may answer differently - but it is no longer credited
with the speed-up. What the cross-vendor repeat *does* prove is that the **mechanism** is
SoC-agnostic: ranking cores by live `cpufreq` instead of hardcoding a mask finds the performance
cluster unchanged on MediaTek and Qualcomm.

### The two optimizations that do pay on Arm

**1. KleidiAI only accelerates Q4_0 and Q8_0.** Arm's KleidiAI registers matmul kernels for exactly
two GGML types. Every other type, including the whole K-quant family, falls back to generic ggml no
matter which of the seven backend variants loaded. **Every benchmark ENTITY published before v2.1.0
used Q3_K_L, so KleidiAI never executed once.** Same phone, same 512-token prompt, same four-thread
unpinned config, only the quantization differs:

| | Q3_K_L (KleidiAI cannot run) | Q4_0 (KleidiAI runs) | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** | **+183%** |
| Time to first token | 12,050 ms | **4,299 ms** | **-64%** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s | -13% |

Prompt eval is a compute-bound GEMM, which is what KleidiAI is built for. Decode is
memory-bandwidth-bound - it tracks bytes-per-weight, not kernel quality - so it does not improve;
Q4_0 is ~6% more bytes and lands slightly slower. ENTITY now reads the GGUF header and tells the
user whether their model can reach Arm's kernels, rather than printing "KleidiAI" regardless.

**2. Widening prompt processing to all cores was a regression.** Auto used to give prompt eval every
online core, assuming a compute-bound phase wants all the hardware. An A55 is about a third of an
A78's throughput, so the widened pool finished late and every GEMM waited on the stragglers.
Measured: prompt on 4 fast cores **135 tok/s**, spread across all 8 **86 tok/s**. Both phases now run
on the fast-core thread count.

**Combined, for the user:** on Llama-3.2-1B, ENTITY Auto, unplugged, time-to-first-token went from
**13,440 ms to 3,918 ms - a 3.4x improvement.** Decode gives up ~12%, the bandwidth cost of the
larger quantization; the benchmark screen shows both sides of that trade.

See [`benchmarks/BENCHMARKS.md`](benchmarks/BENCHMARKS.md) for the complete current record and
its limits. The raw historical Termux output remains separate because it uses different models,
flags, workloads, and CLI-only realtime priority.

---

## How It's Optimized for Arm

### 1. Big-Core Affinity via `sched_setaffinity`

The Dimensity 7300 is asymmetric. By default, the Android scheduler spreads threads across all 8 cores; the slow A55s become stragglers, delaying the whole pipeline. ENTITY pins inference threads to cores 4–7 (the Cortex-A78 cluster) using `sched_setaffinity`, with core indices **auto-detected from live `cpufreq` rankings** in `/sys/devices/system/cpu/` — no hardcoded core mask, which is why the identical path works on Qualcomm. Together with the thread-count decision it produces the in-app result of **8.0 → 17.7 tok/s decode (+121%)** on the Q3_K_L 1B workload. The app's threads-only arm has since measured how that splits: **the thread count earns essentially all of it and the pinning earns ~0%.** The pinning is kept because it is free and another SoC may differ, but it is not what makes ENTITY fast.

**Impact:** on generation (memory-bandwidth-bound workload), pinning to big cores recovers the speed lost to the LITTLE cluster.

### 2. Universal Arm Support via Runtime CPU Backend Dispatch

v1.x compiled a single backend for a known SoC. v2.0.0 reverses this: ENTITY now ships **7 Arm CPU backend variants** (armv8.0, armv8.2×2, armv8.6, armv9.0, armv9.2×2) and uses ggml's dynamic loader to pick the best one at startup. **No performance regression** on the reference device's dotprod path — KleidiAI kernels are compiled into every variant, verified. Still **arm64-v8a only** (x86 dropped).

This makes ENTITY run on essentially any Arm Android phone: old phones without dotprod get the armv8.0 fallback (no SIGILL crash), new phones with i8mm/SME run the optimized variants for those ISAs. The first-run dialog shows what was detected and suggests Auto mode.

**Impact:** universal Arm compatibility without a single hardcoded SoC assumption. Trade-off: APK size is larger (~9.8 MB release, up from ~7 MB at v1.7.0) because multiple kernels ship. A custom build could set `GGML_CPU_ALL_VARIANTS=OFF` and target a specific `GGML_CPU_ARM_ARCH` to go back to a single backend.

### 3. Adaptive Context Window

The phone has 6 GB total RAM, but runtime headroom can be only ~1.5–2 GB after Android's baseline. ENTITY computes the context window from the GGUF size and free RAM. For a 3B-class model, Auto uses **4096 tokens only above 2.2 GB free RAM** and uses **2048 tokens at or below that threshold**. llama.cpp memory-maps the model weights, while the adaptive context keeps KV-cache demand within the available headroom.

**Impact:** the context policy makes larger models more usable on constrained devices without
promising a single context size for every memory condition.

### 4. Quantization Insight: Q4_0 Can Beat Smaller Formats on Arm

On Arm CPU inference, a smaller GGUF is not automatically faster. Q4_0 can beat a smaller
three-bit IQ format when its dotprod kernel path is better supported. Generation is often
memory-bandwidth bound, so kernel efficiency can matter more than raw bit width. This remains
model- and device-dependent; ENTITY exposes the model quantization and includes an in-app
benchmark so the choice can be tested on the actual phone.

**Impact:** format plus kernel alignment can beat raw size shrinking. Q4_0 is a sensible starting
point for this hardware class, but it is not presented as a universal winner.

### 5. Big-Core Pinning Under Contention (CLI-Benchmarked Ceiling)

The app implements affinity pinning only (`sched_setaffinity`, item #1) — it does **not** call `sched_setscheduler`/SCHED_RR or request realtime priority anywhere in `ai_chat.cpp`. To understand how far the same pinning technique goes under adversarial conditions, I additionally benchmarked it from the command line (Termux `llama-cli`/`llama-bench`), there combining `-Cr 4-7 --cpu-strict 1` (the app's affinity pinning) with the CLI-only flag `--prio 3` (realtime scheduler priority) to protect the workload from OS preemption under contention. With a background download contending for CPU, the naive config collapsed to **0.7 tok/s** on the 3B model while the pinned + realtime CLI configuration held **4.4 tok/s** — a **~6.3× gap**.

This is an honest, CLI-measured ceiling for the technique, not a claim about the shipped app: ENTITY ships the affinity-pinning half of this combination; realtime priority is not something an unprivileged Android app can request the way a Termux CLI process can. The raw output is retained in `benchmarks/termux_master_results.txt`.

**Impact:** demonstrates that affinity pinning's value compounds under real-world contention (heat, background load, battery); the app captures the pinning portion of that gain today.

### 6. Energy Efficiency as a Measurable First-Class Metric

ENTITY measures battery current and voltage (via Android's BatteryManager, no root needed) and computes **power (W) = |I µA| × V mV / 10⁹**, then reports **tokens/sec/watt** during inference. This reframes on-device AI from a "peak speed" story to an "efficiency under sustained load" story — the metric that actually matters on a phone.

Measured in the current in-app run: the optimized configuration achieves **2.5× better
tokens-per-watt** (1.7 → 4.2 tok/W) while drawing *lower* power (4.7 → 4.0 W) than naïve
eight-core execution.

**Impact:** energy transparency. Users and developers can now see the actual battery cost of inference on their device.

---

## Setup Instructions

### Prerequisites
- Android SDK with **compileSdk 36** (Android 16) and matching build-tools, **NDK 27.1.12297006**, and **CMake 3.31.6**.
- **JDK 17**.
- llama.cpp master branch.
- GGUF models (Llama-3.2-1B or -3B Q4_0 recommended).

### Build & Install

1. **Download llama.cpp and place the app:**

   ```bash
   curl -sL -o llama.tar.gz \
     https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
   tar xzf llama.tar.gz
   cp -r app/entity.android llama.cpp-master/examples/entity.android
   ```

   (Or run `setup.sh` in this repository to automate this.)

2. **Configure the toolchain:**

   ```bash
   export JAVA_HOME=/path/to/jdk-17
   export ANDROID_HOME=/path/to/Android/sdk
   export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
   ```

3. **Build the APK:**

   ```bash
   cd llama.cpp-master/examples/entity.android
   ./gradlew :app:assembleDebug --no-daemon --console=plain
   # Output: app/build/outputs/apk/debug/app-debug.apk (~40 MB debug build)
   ```

4. **Install and add models:**

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk

   # Push a GGUF model to the device
   DIR=/sdcard/Android/data/com.entity.chat/files/models
   adb shell mkdir -p $DIR
   adb push Llama-3.2-1B-Instruct-Q4_0.gguf $DIR/
   ```

   Download models from [HuggingFace](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF) (Q4_0 variant recommended).

   > **Note:** `adb push` works here because `adb` can write directly into the app's private `Android/data/` storage — it's the fast path for developers. End users instead add models via the in-app **Import from device** picker (⋮ → Select model → **Import from device…**), which uses Android's Storage Access Framework and works from any storage location without adb. That picker, not `adb push`, is the app's real model-loading path.

5. **Run and validate:**

   ```bash
   adb shell am start -n com.entity.chat/com.example.llama.MainActivity
   ```

   - Open the app.
   - Tap the folder icon to import or load a model from the device.
   - Chat normally.
   - To validate the optimization: ⋮ → **Benchmark** to run the in-app three-arm ablation (naïve, threads-only, Auto) on the loaded model, then **Export CSV** for the per-pass evidence.

### CPU Backend Configuration

The current v2.0.0 release uses `GGML_CPU_ALL_VARIANTS=ON`. It ships seven Arm CPU backend
variants and lets ggml select the best supported one at runtime. The same APK therefore supports
older arm64 CPUs without dotprod and newer CPUs with i8mm, SVE2, or SME while retaining KleidiAI
kernels in every variant.

A distribution that intentionally targets one known SoC can instead set
`GGML_CPU_ALL_VARIANTS=OFF` and choose `GGML_CPU_ARM_ARCH`. That produces a smaller
single-backend build but removes the portability of the normal v2 release. See
[`docs/BUILD.md`](docs/BUILD.md) for exact target examples and safety notes.

---

## Significant Updates During Submission Period

ENTITY was **newly created during the hackathon submission period**, built from scratch. The version history demonstrates rapid iteration:

- **v1.0.0** (2026-07-02): Initial release. Core optimizations (big-core affinity, device-tuned backend, adaptive context) + chat UI + live metrics.
- **v1.1.0** (2026-07-03): Added live metrics graph (6 independently toggleable series), settings screen with Auto/manual tuning, Stop/New Chat buttons, and About/Optimizations page.
- **v1.2.0** (2026-07-03): In-app model import via Storage Access Framework; model info card reading GGUF headers; fixed model loading on modern Android (scoped storage).
- **v1.3.0** (2026-07-03): In-app benchmark — naïve-vs-optimized PP 512 / TG 128 test side-by-side, reporting speed + power + tokens-per-watt on the loaded model.
- **v1.4.0** (2026-07-04): Theme-aware app-icon switcher; header chips for temperature and free RAM; fixed benchmark race condition and CPU-affinity leak.
- **v1.5.0** (2026-07-04): Branded empty state (ENTITY mark + tagline); stat row redesigned to sans-serif for polish.
- **v1.6.0** (2026-07-10): Chat persistence (multiple conversations, restore on launch, seamless context re-priming), prompt-processing/generation thread split in the native layer, graceful context-full trimming, Markdown rendering, system-prompt editor, multi-run benchmark with median ± stddev / TTFT / CSV export, UI polish + Animations toggle, and a stripped R8 release build.
- **v1.7.0** (2026-07-12): Efficiency mode (Settings toggle capping inference at 2 threads, doubling thermal delays), periodic thermal guard via `ThermalGuard` every eight tokens (NONE/LIGHT → 0 ms, MODERATE → 6 ms, SEVERE+ → 12 ms), 5-sample windowed power sampling eliminating jitter, proper release signing with dedicated keystore, and 5 new unit tests covering thermal logic.
- **v2.0.0** (2026-07-12): Universal Arm support via **7 CPU backend variants with runtime dispatch** (no SIGILL on old cores, no missed optimizations on new ones); first-run device optimization dialog; power measurement bug fix (OEM kernel milliamp/microamp confusion); benchmark corrected to measure shipped Auto config; SoC-neutral UI strings; and 8 additional unit tests for device detection and power math.

**All versions from v1.6.0 onward ship with both prebuilt debug and release-signed APKs in [`apk/`](apk/)** (see `apk/README.md`) and as copy-paste-ready notes in [`releases/`](releases/), installable via `adb install -r`. Versions 1.0–1.5 are available as debug APKs only.

---

## Repository & License

- **Public repository:** [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge). It contains the Gradle project in `app/entity.android/`, prebuilt APKs in `apk/`, versioned release notes in `releases/`, measurements in `benchmarks/`, technical documentation in `docs/`, and Termux helpers in `scripts/`.
- **Apache License 2.0** — see `LICENSE` file. Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) and Arm [KleidiAI](https://gitlab.arm.com/kleidi/kleidiai) (Apache-2.0).

---

## Summary

ENTITY proves that **optimization for real Arm hardware is the leverage point** for on-device AI on phones. By treating the phone as an asymmetric big.LITTLE SoC with thermal and power constraints - instead of a small desktop - the shipped Auto path decodes **+81% to +94% faster than the out-of-the-box eight-thread default**, a gain its own three-arm ablation attributes to the thread count rather than core pinning. The KleidiAI Q4_0 finding cut **time-to-first-token from 13.4 s to 3.9 s**, and on the same phone and model ENTITY beats **Arm's own AI Chat app by 11% on prompt and 21% on token generation** - while the adaptive runtime still fits a 3B model into 2 GB of free RAM on a $200 phone.

The submission is reproducible, measured, and honest about trade-offs. The code is open-source. The results are on-device.
