# ENTITY v2.0.0 Technical Reference

This is the current technical overview for ENTITY. It describes the app that ships in the v2.0.0
release. Versioned release notes and the older Termux experiments are preserved as historical
evidence and are not interchangeable with the current Android app result.

Source repository: [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

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

## Runtime decisions

### CPU selection

Native code reads cpuinfo maximum frequency for every CPU, sorts the cores, and selects the fastest
generation set. In Auto mode the generation thread count is online cores minus two clamped to the
range two through four. The selected set is passed to sched_setaffinity.

Decode stays on the fast core set. Prompt processing can use all online cores through a separate
ggml thread pool. If that runtime thread pool API is unavailable, the app falls back safely to the
fast core set for both paths.

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
tokens per watt. It evaluates both the microamp and milliamp interpretations of current, then
uses the result within the plausible phone range of 0.05 W through 15 W. This avoids the
thousandfold error produced by OEM kernels that expose milliamps instead of documented microamps.

During a benchmark the app samples power every 150 ms and averages valid values. It hides power
and energy metrics when charging because USB input makes battery current invalid for comparison.

Implementation: PowerMath, MainActivity.snapMetrics, and BenchmarkActivity.runPass.

## Current in app benchmark

The canonical app result uses Llama 3.2 1B Instruct Q3 K L with PP 512 and TG 128. Each
configuration runs three times on an unplugged phone. Values are median plus population standard
deviation.

### CMF Phone 1

| Metric | Naive eight cores | ENTITY Auto | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.2 ± 0.34 tok per s | 43.2 ± 1.8 tok per s | +2% |
| Decode throughput | 8.0 ± 1.1 tok per s | 17.7 ± 0.56 tok per s | +121% |
| Derived TTFT | 12245 ± 108 ms | 11907 ± 452 ms | 3% lower |
| Power | 4.7 ± 0.34 W | 4.0 ± 0.22 W | lower |
| Energy efficiency | 1.7 ± 0.36 tok per W | 4.2 ± 0.23 tok per W | 2.5× |

### Snapdragon 6 Gen 4

| Metric | Naive eight cores | ENTITY Auto | Change |
|---|---:|---:|---:|
| Prompt throughput | 39.3 ± 2.2 tok per s | 47.7 ± 0.12 tok per s | +21% |
| Decode throughput | 6.0 ± 1.1 tok per s | 13.1 ± 0.05 tok per s | +117% |
| Derived TTFT | 13194 ± 672 ms | 10811 ± 28 ms | 18% lower |
| Power | 3.4 ± 0.15 W | 3.4 ± 0.29 W | flat |
| Energy efficiency | 1.8 ± 0.24 tok per W | 3.8 ± 0.31 tok per W | 2.1× |

TTFT is a benchmark estimate from one prompt evaluation and one decode step. It is not a
live chat first token measurement.

## Evidence and reproduction

1. benchmarks/BENCHMARKS.md is the single current method, result, caveat, and reproduction record.
2. benchmarks/termux_master_results.txt is raw historical CLI output. It is not an app benchmark.
3. docs/BUILD.md contains the exact build and validation path.
4. docs/ARCHITECTURE.md maps the Kotlin UI, JNI layer, and native inference flow.
5. docs/OPTIMIZATIONS.md connects each shipped optimization to its source file.
6. docs/FAQ.md answers common questions about models, Auto mode, device support, and metrics.

## Historical work

The Termux results remain useful for thread scaling, quantization behavior, thermal observations,
and contention analysis. They used other models, flags, workloads, and in some cases realtime
priority. Treat them as design evidence only. The v2 Android app benchmark above is the headline
result for this release.
