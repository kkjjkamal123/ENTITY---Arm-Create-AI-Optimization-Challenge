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
four cores for decode, can widen prompt processing to all online cores, and sizes context from
model size plus available memory. It also enables the thermal guard.

## Why not use every CPU core for generation?

Decode is often limited by memory bandwidth. On a big.LITTLE phone, waiting for efficiency cores
can slow the token-by-token path. ENTITY keeps decode on the fast frequency-ranked core set while
allowing the more parallel prompt phase to use a wider pool.

## How does ENTITY avoid running out of memory?

Auto mode uses the GGUF file size and free RAM to choose a 2048, 4096, or 8192 token context. It
reduces KV-cache pressure before a larger model makes the app unstable. Manual mode leaves the
context decision to the user.

## Is a smaller quantization always faster?

No. Kernel support and memory bandwidth matter as much as file size. Q4_0 is a sensible starting
point on this hardware class because its dotprod path can be efficient, but the best model depends
on the phone and the specific GGUF. Use the in-app benchmark to compare candidates on the device.

## Are power and efficiency numbers trustworthy while charging?

No. Charging changes the battery-current reading, so ENTITY hides power and tokens-per-watt during
charging. For comparable results, unplug the phone, let it cool, and run the same model and test
configuration.

## What do the published benchmark numbers mean?

They compare a naïve eight-core configuration with the same Auto path used by chat. The current
record uses Llama 3.2 1B Instruct Q3 K L with PP 512 and TG 128. Read the full method, results,
and limits in [BENCHMARKS.md](../benchmarks/BENCHMARKS.md).

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
