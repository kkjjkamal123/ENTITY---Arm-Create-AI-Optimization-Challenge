# ENTITY documentation index

Start here. This page is the map: what each document covers, and the order to read them in.
The technical summary below tracks the current release (chat **v3.6.2**, ENTITY Bench **v2.1.1**).

Versioned release notes and the older Termux experiments are preserved as historical evidence and
are not interchangeable with the current Android app result.

## Reading order

**Understand the project**
1. [`../README.md`](../README.md) - what ENTITY is, install, screenshots.
2. [`../github.md`](../github.md) - the full narrative, including the falsification arcs.
3. [`FAQ.md`](FAQ.md) - the short answers, including what is deliberately *not* claimed.
4. [`JOURNEY.md`](JOURNEY.md) - every claim that had to be withdrawn, why it survived, and what
   replaced it. If you want to know whether to trust the numbers, read this one.

**Understand how it works**
5. [`ARCHITECTURE.md`](ARCHITECTURE.md) - module layout, threading model, file-by-file inventory.
6. [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md) - the algorithms: core selection, thread widths, context
   admission, thermal policy, power math, and the limit of each claim. The deepest document.
7. [`KLEIDIAI-QUANTS.md`](KLEIDIAI-QUANTS.md) - which quantizations reach which Arm kernels.

**Understand the evidence**
8. [`../benchmarks/README.md`](../benchmarks/README.md) - the results at a glance.
9. [`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md) - the multi-device
   dataset: what it established, what it falsified, and the rules for reading it.
10. [`../benchmarks/REPRODUCIBILITY.md`](../benchmarks/REPRODUCIBILITY.md) - how to re-run it.
11. [`../benchmarks/COMPARISONS.md`](../benchmarks/COMPARISONS.md) - against other apps.

**Build, contribute, extend**
12. [`BUILD.md`](BUILD.md) - toolchain and build.
13. [`CONTRIBUTING.md`](CONTRIBUTING.md) - conventions and validation.
14. [`../benchmarks/CONTRIBUTE-BACKEND.md`](../benchmarks/CONTRIBUTE-BACKEND.md) - the results
    backend, if you are forking it.

**History**
15. [`../CHANGELOG.md`](../CHANGELOG.md) and [`../releases/`](../releases/) - per-version detail.

Source repository: [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

## The project site

**<https://kkjjkamal123.github.io/ENTITY-WEB/>** presents the same material for reading rather
than for auditing: animated explainers of each optimization, a **live device leaderboard** that
queries the contributed dataset directly, and the falsification record. Nothing there is a new
claim - every figure is labelled `measured` or `schematic` and traces to a named file in this
repository, and the map from page to source file is
[`CONTENT-SOURCES.md`](https://github.com/kkjjkamal123/ENTITY-WEB/blob/main/CONTENT-SOURCES.md)
in [kkjjkamal123/ENTITY-WEB](https://github.com/kkjjkamal123/ENTITY-WEB).

Read the site to understand the project. Read this repository to check it.

## Current release

ENTITY is a fully offline Android LLM runtime for arm64 phones. It combines a Kotlin interface,
a C++ JNI inference layer, llama.cpp, and Arm KleidiAI kernels.

The v2 release ships seven Arm CPU backend variants. ggml loads the strongest variant supported by
the device at startup. This removes the old single SoC assumption: arm64 devices without dotprod
use a safe fallback while newer devices can use i8mm, SVE2, or SME when supported.

The app has been measured on:

| Device | SoC | Memory | Android |
|---|---|---:|---:|
| CMF Phone 1 | MediaTek Dimensity 7300 | 6 GB | 16 |
| OPPO CPH2729 | Qualcomm Snapdragon 6 Gen 4 | 7.4 GB | 16 |

Plus four SoCs measured by contributors on devices the author does not own - Tensor G5, SM8550,
SM8450 and MT6878 - see
[`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md).

## Runtime decisions

### CPU selection

Native code ranks every CPU by strength and pins inference to the strongest set. Ranking prefers
`/sys/devices/system/cpu/cpuN/cpu_capacity` - the kernel's own normalised capacity, 1024 = the
strongest core - and falls back to `cpuinfo_max_freq` where a kernel does not export it. Frequency
alone cannot rank cores correctly: an A55 at 2.0 GHz and an A78 at 2.5 GHz are 25% apart in clock
and roughly 3x apart in throughput.

The two inference phases run **different thread widths**, because they are bound by different
things:

| phase | width | rule |
|---|---|---|
| decode (`n_gen`) | narrow | cores within 10% of the fastest **clock**, clamped [2, 6] |
| prefill (`n_pp`) | performance cluster | cores strictly above the slowest **capacity** tier, >= `n_gen`, capped at 6 |

Decode is memory-bandwidth-bound and saturates on a handful of cores; prefill is compute-bound and
scales with width. On a 4+4 device both rules give the same answer. On a prime-core flagship they
do not, and conflating them was a real bug - see below.

This width has been wrong twice, in opposite directions. It was first "every online core", which
lost to efficiency-core stragglers: prompt throughput on a 1B Q4_0 measures 135 tok/s on the four
fast cores and 86 spread across all eight. The correction was `n_pp = n_gen`, which fixed the 4+4
case and silently broke every prime-core flagship - prefill ran on two threads there until v3.5.0.
Full account in [`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md).

Core **placement** is a user setting since v3.5.0 (Settings -> Inference -> Core placement: Auto /
Perf cores / Scheduler). Pinning is a speed lever with a power cost and it lands differently per
device, so the app measures both and reports which won on that phone rather than asserting one.

Implementation: app/entity.android/lib/src/main/cpp/ai_chat.cpp.

### Context selection

Auto mode uses the loaded GGUF file size and current free RAM:

| Model file size | Free RAM | Running context |
|---|---:|---:|
| Below 1.6 GB | Above 3.0 GiB | 8192 tokens |
| Below 1.6 GB | 3.0 GiB or less | 4096 tokens |
| 1.6 GB or above | Above 2.2 GiB | 4096 tokens |
| 1.6 GB or above | 2.2 GiB or less | 2048 tokens |

The policy lowers KV cache pressure before large models consume limited memory. Manual mode uses
the context selected by the user instead.

Implementation: MainActivity.adaptiveContext and ai_chat.cpp init_context.

### Thermal policy

In Auto mode the app reads a cached Android thermal status every eighth generated token:

| Status | Delay |
|---|---:|
| NONE or LIGHT | 0 ms |
| MODERATE | 6 ms |
| SEVERE or above | 12 ms |

The status cache refreshes at most once per second. Efficiency mode doubles the chosen delay and
caps inference at two threads. The app uses a cooperative delay rather than privileged realtime
scheduling or root only thermal sensors.

Implementation: ChatViewModel ThermalGuard and thermalStatus.

### Energy measurement

ENTITY reads battery current and voltage through Android BatteryManager and reports watts and
tokens per watt. The voltage unit is resolved first (`normalizeVoltageMv()`, since v3.6.0): under
100 is volts, over 100,000 is microvolts, otherwise millivolts - the three ranges are three orders
of magnitude apart, so magnitude alone identifies the unit. Only then does it evaluate both the
microamp and milliamp interpretations of current and keep the result within the plausible phone
range of 0.05 W through 15 W. Both steps exist because OEM kernels have been observed reporting
each quantity in the wrong unit - milliamps where microamps are documented, volts where millivolts
are documented - and a wrong voltage silently defeats the current-unit heuristic alone (both
candidate wattages fall outside the plausible range at once, see the v3.6.0 release notes).

During a benchmark the app samples power every 150 ms and averages valid values. It hides power
and energy metrics when charging because USB input makes battery current invalid for comparison.

Implementation: PowerMath, MainActivity.snapMetrics, and BenchmarkActivity.runPass.

## Current in app benchmark

**Do not attribute the shipped gain to core pinning - that attribution was wrong and has been
withdrawn.** The two-arm result below (eight threads vs. ENTITY Auto) was the original submission
benchmark; it changes two variables at once, thread count and core placement, and credits the pin
with a gain the pin does not fully earn. A three-arm ablation isolated the two: the thread count
earns +81% to +106% of decode on every device and model measured, and is the larger share
everywhere. The pin itself is real but smaller and device-dependent - a later four-arm, five-run
export on two vendors' silicon puts it at **+21% decode on the Dimensity 7300** and **+1% decode
but a real power saving on the Snapdragon 6 Gen 4**. Both corrections, with every raw CSV, are in
[`docs/JOURNEY.md`](JOURNEY.md) and [`benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md) -
treat those two as the current numbers, not the table historically reproduced below.

### Historical: the original two-arm result (superseded, kept for the record)

| Metric | Naive eight cores | ENTITY Auto | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.2 ± 0.34 tok per s | 43.2 ± 1.8 tok per s | +2% |
| Decode throughput | 8.0 ± 1.1 tok per s | 17.7 ± 0.56 tok per s | +121% (mis-attributed, see above) |
| Derived TTFT | 12245 ± 108 ms | 11907 ± 452 ms | 3% lower |
| Power | 4.7 ± 0.34 W | 4.0 ± 0.22 W | lower |
| Energy efficiency | 1.7 ± 0.36 tok per W | 4.2 ± 0.23 tok per W | 2.5× |

CMF Phone 1, Llama 3.2 1B Instruct Q3_K_L, PP 512 / TG 128, three runs, unplugged, median ±
population standard deviation. The Snapdragon 6 Gen 4 run of the same protocol, and the corrected
three/four-arm numbers for both devices, are in `benchmarks/BENCHMARKS.md`.

TTFT is a benchmark estimate from one prompt evaluation and one decode step. It is not a
live chat first token measurement.

## Evidence and reproduction

1. benchmarks/BENCHMARKS.md is the single current method, result, caveat, and reproduction record.
2. benchmarks/COMPARISONS.md defines the three arm in app ablation and the upstream llama.cpp
   baseline protocol, and states what an ExecuTorch or MLC-LLM claim would require.
3. benchmarks/REPRODUCIBILITY.md is the protocol, the CSV evidence schema, and the logcat check
   that confirms the three arms really ran on different cores.
4. benchmarks/termux_master_results.txt is raw historical CLI output. It is not an app benchmark.
5. docs/BUILD.md contains the exact build and validation path.
6. docs/ARCHITECTURE.md maps the Kotlin UI, JNI layer, and native inference flow.
7. docs/OPTIMIZATIONS.md connects each shipped optimization to its source file.
8. docs/KLEIDIAI-QUANTS.md is a standalone guide to which GGUF quantizations reach Arm's KleidiAI kernels and what the rest cost.
9. docs/FAQ.md answers common questions about models, Auto mode, device support, and metrics.
10. templates/arm64-android-runtime/ is a copyable starter kit for other Arm64 Android projects.

## Historical work

The Termux results remain useful for thread scaling, quantization behavior, thermal observations,
and contention analysis. They used other models, flags, workloads, and in some cases realtime
priority. Treat them as design evidence only. The v2 Android app benchmark above is the headline
result for this release.
