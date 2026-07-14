# ENTITY FAQ

[Home](../README.md) · [Benchmarks](../benchmarks/BENCHMARKS.md) · [Optimization](OPTIMIZATIONS.md) · [Starter kit](../templates/arm64-android-runtime/README.md) · [Contributing](CONTRIBUTING.md) · [License](../LICENSE)

## Does ENTITY need internet access?

No. Model loading, prompt processing, generation, chat storage, and runtime metrics run locally on
the phone. ENTITY does not require an inference server or a cloud API.

## Which devices are supported?

The current release targets arm64 Android phones running Android 13 or later. It ships seven Arm
CPU backend variants and selects the strongest variant supported by the device at runtime. The
release does not include x86 or x86_64 binaries.

## Which models can I use?

Import a runnable GGUF model through the in-app document picker. Model weights are not bundled
with the APK. A 1B model is a good starting point; a 3B class model needs more free memory and may
receive a smaller Auto context window.

## What does Auto mode change?

Auto mode ranks online CPU cores by their advertised maximum frequency, uses the fastest two to
four cores for both inference phases, and sizes context from
model size plus available memory. It also enables the thermal guard.

## Why not use every CPU core?

Because it is measurably slower — for **both** phases, which was a surprise.

Decode is limited by memory bandwidth, and on a big.LITTLE phone the efficiency cores gate the
token-by-token path: 8 threads gives 8.8 tok/s, 4 threads gives 16.9.

Prompt eval is compute-bound, so ENTITY used to widen it to all 8 cores. That was a regression: an
A55 is about a third of an A78's throughput, so every GEMM waited on the stragglers. Prompt on 4
fast cores measures 135 tok/s; across all 8 it measures 86. Since v2.1.0 both phases run on the
fast-core set.

## How does ENTITY avoid running out of memory?

Auto mode uses the GGUF file size and free RAM to choose a 2048, 4096, or 8192 token context. It
reduces KV-cache pressure before a larger model makes the app unstable. Manual mode leaves the
context decision to the user.

## Is a smaller quantization always faster?

No — and on Arm this is the single most expensive thing to get wrong.

**Arm's KleidiAI ships matmul kernels for Q4_0 and Q8_0 only.** Every other quantization, including
the entire K-quant and IQ family, falls back to generic ggml no matter which CPU backend variant the
app loaded. A Q3_K_L model is smaller on disk than Q4_0 and still leaves Arm's kernels completely
idle.

Measured on a Dimensity 7300, same phone and thread config, only the quant differing:

| | Q3_K_L (733 MB) | Q4_0 (773 MB) |
|---|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** |
| Time to first token | 12.1 s | **4.3 s** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s |

Prompt eval is a compute-bound GEMM, which is what KleidiAI accelerates. Decode is bandwidth-bound
and tracks bytes-per-weight, so the slightly larger Q4_0 is slightly slower there. ENTITY's
model-info card tells you which case you are in when you load a model.

## Are power and efficiency numbers trustworthy while charging?

No. Charging changes the battery-current reading, so ENTITY hides power and tokens-per-watt during
charging. For comparable results, unplug the phone, let it cool, and run the same model and test
configuration.

## What do the published benchmark numbers mean?

They compare a naïve eight-thread configuration with the same Auto path used by chat. The current
record uses Llama 3.2 1B Instruct Q3 K L with PP 512 and TG 128. Read the full method, results,
and limits in [BENCHMARKS.md](../benchmarks/BENCHMARKS.md).

They are the gain of the shipped configuration over the out-of-the-box default. They do not say
how much of it comes from core pinning as opposed to simply using fewer threads, because those two
arms change both at once.

## So how much does the core pinning actually earn?

**About 0%.** ENTITY's own ablation disproved ENTITY's flagship optimization.

The Benchmark screen runs a third arm — threads-only: Auto's thread count with affinity switched
off, which is what an upstream `llama.cpp -t 4` run does. Across six runs on two models, dropping
8 threads to 4 earns +81% to +94% of decode, and pinning those threads to the performance cluster
adds nothing measurable.

The affinity code still ships, because it is free and another SoC may behave differently. It is
simply no longer claimed as the reason ENTITY is fast. Run the benchmark on your own phone and the
numbers are yours: [full record](../benchmarks/BENCHMARKS.md).

## Does ENTITY use realtime scheduling or root-only controls?

No. The Android app uses standard CPU affinity and a cooperative thermal delay. Historical Termux
experiments with realtime priority are kept separate and are not claimed as app behavior.

## Where can I see the full optimization algorithm?

[OPTIMIZATIONS.md](OPTIMIZATIONS.md) documents the native implementation, core selection, context
admission, thermal policy, power math, benchmark statistics, and the limits of each claim.

## How can I contribute or report an issue?

Read [CONTRIBUTING.md](CONTRIBUTING.md) for build validation, project conventions, and next steps.
The canonical source is [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

## Can I reuse the Arm runtime logic in another Android project?

Yes. The [Arm64 Android starter kit](../templates/arm64-android-runtime/README.md) contains the
pure Kotlin runtime policy, a portable C++ affinity helper, and a retargeting checklist. It is a
starting point, not a replacement for testing on the target phone.
