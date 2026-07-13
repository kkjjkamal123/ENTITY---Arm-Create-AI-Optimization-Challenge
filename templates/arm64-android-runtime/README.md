# Arm64 Android LLM runtime starter

[Home](../../README.md) · [Optimization reference](../../docs/OPTIMIZATIONS.md) · [Benchmarks](../../benchmarks/BENCHMARKS.md) · [Contributing](../../docs/CONTRIBUTING.md)

This small starter kit packages the reusable parts of ENTITY's Auto runtime for another Android
LLM project. It is deliberately framework-free: one Kotlin policy object and one C++ affinity
helper. Copy the files, connect them to your inference runtime, then benchmark on the target phone.

## Included artifacts

| File | Use it for |
|---|---|
| [AdaptiveRuntimePolicy.kt](AdaptiveRuntimePolicy.kt) | Context admission, Auto decode width, thermal delay, power-unit resolution, TTFT, median, and population standard deviation. |
| [fast_core_affinity.h](fast_core_affinity.h) | Frequency-ranked Linux/Android CPU selection and `sched_setaffinity` for the calling inference thread. |
| [device-result-template.csv](../../benchmarks/device-result-template.csv) | Consistent device-result rows for a shared performance matrix. |

## Integration checklist

1. Copy `AdaptiveRuntimePolicy.kt` into the Kotlin module that owns runtime settings.
2. Call `generationThreads(Runtime.getRuntime().availableProcessors())` for the Auto decode width.
3. Call `adaptiveContext(model.length(), availableMemoryBytes)` before creating the inference
   context. Preserve a manual override if your UI offers one.
4. Add `fast_core_affinity.h` to the native module. Build a `FastCoreSet` before worker creation,
   then call `pin_current_thread` on each native inference entry point or use the selected CPUs in
   your runtime's worker-pool affinity API.
5. Poll Android thermal status on a coarse cadence. Apply `thermalDelayMs` cooperatively rather
   than requesting realtime priority.
6. Sample `BATTERY_PROPERTY_CURRENT_NOW` and battery voltage only when unplugged. Use `watts` and
   `tokensPerWatt` for comparable power data.
7. Compare a naïve all-core baseline with Auto using the same model, prompt size, generated-token
   count, thermal starting point, and number of passes.
8. Record a row using `device-result-template.csv`, together with the raw CSV from your benchmark.

## Retargeting notes

The affinity algorithm is SoC-neutral because it ranks live CPU frequencies. The native CPU backend
is a separate build decision. ENTITY v2 ships seven arm64 CPU backend variants and dynamically
selects one at runtime. If you ship one fixed backend instead, choose only instructions confirmed on
the target CPU; forcing an unsupported instruction can cause `SIGILL`.

Prompt evaluation and token generation behave differently. A good default is a small fast-core set
for decode and a wider pool for prompt processing. Do not assume that more threads or a smaller
quantization is always faster; validate the model, phone, and thermal state you intend to ship.

## What this kit does not provide

It does not include llama.cpp, a model, a UI, root-only controls, or a guaranteed performance gain.
It gives another developer a tested starting policy and a reproducible way to measure whether that
policy helps their own Arm64 Android target.
