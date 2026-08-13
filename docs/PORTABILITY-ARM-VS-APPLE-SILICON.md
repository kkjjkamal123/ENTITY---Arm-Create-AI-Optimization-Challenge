# Portability: Arm/Android against Apple silicon

**Six of ENTITY's ten optimization mechanisms cannot be reproduced on Apple silicon — a
platform that is also arm64.**

That sentence is the point of this document, and it is a measurement rather than a
complaint. Every speedup this project publishes comes from reading and steering the
hardware: which cores are fast, what they are clocked at, where a thread lands, what the
battery is drawing, which Arm ISA extensions the binary can dispatch to. Those mechanisms
exist because the Arm/Android platform *exposes the hardware*. Their absence somewhere else
is the cleanest available estimate of how much of ENTITY's gain is Arm-platform-specific
rather than generic llama.cpp tuning.

Apple silicon is the ideal control. It is arm64, it is a big.LITTLE design, it runs the same
class of quantized model, and it is fast. What it does not do is let an application see or
steer any of it.

## The mechanism table

| # | Mechanism | Android | Apple silicon | Verdict |
|---|---|---|---|---|
| 1 | Core placement | `sched_setaffinity` + pinned ggml threadpool, mask read back with `effective_cpu_mask()` | `sched_setaffinity` does not exist on Darwin; `THREAD_AFFINITY_POLICY` is a hint and a no-op | **redesign — becomes QoS, a request not a pin** |
| 2 | Thread count | parse `cpuinfo_max_freq` per core, count within 10% of fastest | `hw.perflevel0.logicalcpu` reports P-core count exactly | **improves** |
| 3 | Live frequency trace | `scaling_cur_freq` per core, plotted per pass | no `/sys`, no public per-core clock API | **removed** |
| 4 | ADPF performance hints | `APerformanceHint` deadline declaration per decode step | no equivalent public API | **removed** |
| 5 | Thermal policy | thermal status callback + battery temperature | `ProcessInfo.thermalState`, four coarse levels | **ports, degraded** |
| 6 | Energy telemetry | `BATTERY_PROPERTY_CURRENT_NOW` × voltage, integrated over the pass | no public current or voltage API; `batteryLevel` quantized to 5% | **removed** |
| 7 | Adaptive context | context sized from system free memory | `os_proc_available_memory()` — per-process, the number that actually matters | **improves** |
| 8 | Multi-variant CPU backends | seven ggml `.so` variants, v8.0 → v9.2, `dlopen`'d at startup | code outside the signed bundle cannot be loaded | **collapses to one static build** |
| 9 | KleidiAI advisor | GGUF header read, quant type → kernel eligibility | pure logic, no platform dependency | **ports cleanly** |
| 10 | Model-fit catalog | free RAM against model size | ports, with hard per-app jetsam limits instead of advisory ones | **ports, with a new hazard** |

Removed or crippled: 1, 3, 4, 5, 6, 8. Six of ten.

Note which ones improve. This is not an argument that Apple silicon is worse — items 2 and 7
are *better* on iOS, because Apple reports P-core count and per-process memory headroom
directly instead of making you infer them from `/sys`. The asymmetry is specifically about
**steering and observing**, not about capability.

## What the hardware says about itself

From the on-device ISA probe, both iPhones tested:

```
architecture ARMv9 · 6 cores (2 performance, 4 efficiency) · 2 perf levels
neon ✓  fp16 ✓  bf16 ✓  dotprod ✓  i8mm ✓  SME ✓  SME2 ✓   SVE: not queryable
```

This is a *more* capable ISA than most of the nine Android SoCs in this project's fleet.
i8mm is present — the extension the quantization lab could never test because the phones
available to it are `asimddp` only. SME and SME2 are present, which no Android device here
has.

And it makes no difference to what an application can do with it, because item 8 above means
there is exactly one binary and no runtime variant selection to perform.

## What the measurements show

Real inference: an ONNX Runtime 1.27.0 English→Hindi translation build, int8 encoder and
decoder, 285 MB of staged weights with SHA-256 recorded per file, 30 counterbalanced
iterations after 5 warmup rounds, two sentence lengths. Raw exports:
[`benchmarks/results/ios/onnx/`](../benchmarks/results/ios/onnx/).

### The runtime chooses one thread, and it is right to

```
"ortPolicy": { "name": "apple-adaptive(threads=1,affinity=unavailable)",
               "intraThreads": 1, "affinityAvailable": false }
```

The thread sweep confirms the choice on both devices:

| Device | 1 thread | 2 threads | cost of the second thread |
|---|---:|---:|---:|
| iPhone 16 (A18) | 168.8 ms | 197.1 ms | **+16.8%** |
| iPhone 17 Pro Max (A19 Pro) | 179.3 ms | 199.7 ms | **+11.4%** |

**This is the finding.** On Android, thread count is the single largest earner ENTITY has —
the four-arm ablation attributes essentially the whole decode multiplier to it, and the
reason it works is that the naive default lands threads on Cortex-A55s that gate every
decode step. The fix is to *not use the slow cores*.

On Apple silicon you cannot express that. There is no affinity call, so a second thread
cannot be confined to the second P-core; it goes where the scheduler puts it, and the answer
is worse than not asking. The optimization does not fail because the hardware is different.
It fails because the platform will not let you name a core.

### KleidiAI ports and pays, more on the older chip

| Device | KleidiAI on | off | speedup |
|---|---:|---:|---:|
| iPhone 16 (A18) | 173.2 ms | 188.6 ms | **1.089×** |
| iPhone 17 Pro Max (A19 Pro) | 179.6 ms | 187.7 ms | **1.045×** |

Arm's kernels are worth having on Apple silicon, and worth roughly twice as much on the
older SoC. This is the one mechanism in the table that crosses the platform boundary intact
and still earns its keep.

### The newer, more expensive phone is slower

| | iPhone 16 (A18) | iPhone 17 Pro Max (A19 Pro) |
|---|---:|---:|
| tokens/sec | **76.68** | 74.01 |
| long sentence, median | **163.9 ms** | 172.0 ms |
| long sentence, stdev | 4.58 | 2.57 |
| encoder stage, mean | 5.48 ms | **4.91 ms** |

The gap is ~8 ms against stdevs of 2.6 and 4.6, over 30 counterbalanced iterations — outside
noise. The A19 Pro wins the encoder by 10% and loses end to end.

### …until you run it for longer, and then it isn't

Six sustained windows, same workload, median per window:

| Device | first window | last window | degradation | thermal |
|---|---:|---:|---:|---|
| iPhone 16 (A18) | 175.5 ms | 194.5 ms | **1.108×** | nominal → fair |
| iPhone 17 Pro Max (A19 Pro) | 186.2 ms | 186.5 ms | **1.002×** | flat |

The cheaper phone starts faster and ends slower. The Pro Max holds its clock across the
entire run and the iPhone 16 gives up 11%.

Peak throughput and sustained throughput are different products, and only one of them is on
the spec sheet. This is the same lesson the Android side learned from its sustained thermal
mode, arrived at independently on hardware with no thermal instrumentation at all — because
`ProcessInfo.thermalState` reporting `nominal → fair` is the entire thermal signal iOS
offers, and it was enough to see it happen.

## What is deliberately not claimed

**No energy numbers.** iOS exposes no battery current and no voltage; `batteryLevel` is
quantized to 5%. Every export in this repository from an iOS device lists
`battery.currentNowUa` and `battery.chargeCounterUah` under `unavailable`, and that is
honest rather than a harness gap. The tokens-per-watt figures this project publishes are
Android-only and stay that way. A separate SwiftUI benchmark in [`../ios/`](../ios/) emits a
`power_w_est` column that is a **hardcoded constant per arm**; it is kept for completeness,
labelled at length in its own README, and must never be quoted.

**No cross-platform speed comparison.** The Android numbers come from llama.cpp with a GGUF
Llama model; these come from ONNX Runtime with an int8 translation model. Different runtime,
different model, different workload. The two sets are not comparable and no table here puts
them side by side.

**Two devices, not three.** The iPhone 16 Pro Max in the fleet ran only the synthetic
benchmark, so it contributes nothing to this document. A third ONNX data point would
strengthen the thread-sweep result, which currently rests on two devices agreeing.

**One inference workload.** Everything above is a single encoder-decoder translation model
at int8. Whether the one-thread result holds for a larger decoder-only model on Apple
silicon is untested here.

## Why this is a result and not an excuse

It would be easy to read this document as a list of things that could not be built. It is
the opposite. ENTITY's central claim is that a phone runs a language model faster when the
software is designed for the *specific* silicon underneath it rather than treated as a small
desktop. The strongest possible test of that claim is to take the same intent to arm64
hardware that refuses to be steered, and see what survives.

What survived: the quantization advisor, the model-fit logic, and KleidiAI — all of which
are about *choosing well in advance*. What died: every mechanism that involves telling the
hardware what to do at runtime, or watching what it did.

Sixty percent of the optimization surface was platform, not architecture. That number is the
honest scope of the thesis, and it took a second arm64 platform to measure it.

---

Raw data: [`benchmarks/results/ios/`](../benchmarks/results/ios/) ·
iOS sources and builds: [`../ios/`](../ios/) ·
Android record: [`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md)
