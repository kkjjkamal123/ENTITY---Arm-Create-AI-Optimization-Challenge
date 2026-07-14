# ENTITY v2.1.0 — 2026-07-14

**APK:** `ENTITY-v9-kleidiai-quant-20260714-release.apk` (release) · `ENTITY-v9-kleidiai-quant-20260714-debug.apk` (debug)

The headline is uncomfortable and it is the point: **ENTITY's own benchmark disproved ENTITY's
flagship optimization.**

v2.0.0 claimed a +121% decode gain from big-core affinity pinning. This release ships the experiment
that tests that claim — a three-arm ablation — and the answer is that the pinning earns
approximately **nothing**. The gain comes from using four threads instead of eight, which is what
any `llama.cpp -t 4` user already gets.

Having disproved one optimization, the same measurement discipline found two that do pay on Arm, and
together they cut time-to-first-token by **3.4×**.

## 1. The ablation: what actually earns the speed-up

The benchmark now runs three arms instead of two. The middle one holds Auto's thread count and
switches only the core pinning off, so the two variables separate:

| Model | Naïve (8 thr) | Threads-only (4 thr, no pin) | Auto (4 thr, pinned) | Thread count earns | Pinning earns |
|---|---:|---:|---:|---:|---:|
| 1B Q3_K_L (3 runs) | 8.8 ± 0.50 | 16.9 ± 0.08 | 16.7 ± 1.3 | **+92%** | **−1%** |
| 1B Q4_0 | 7.9 | 14.7 | 14.7 | **+86%** | **+0%** |
| 3B Q4_0 | 3.1 | 6.0 | 6.8 | **+94%** | +13% |
| 3B Q4_0 | 3.5 | 6.3 | 6.3 | **+81%** | **+0%** |

The two 3B runs disagree, a third measured −16% while charging, and single 3B runs swing about ±15%.
Across every run, the honest number for pinning is ~0%.

Running eight threads on a 4+4 big.LITTLE phone lets the Cortex-A55s gate every decode step. Using
four threads removes that. Pinning those four to the performance cluster adds nothing measurable.

The affinity code still ships. It is free, another SoC may answer differently, and the *mechanism* —
ranking cores by live `cpufreq` rather than hardcoding a mask — is genuinely SoC-agnostic and proven
so across MediaTek and Qualcomm. What is retired is the claim that it is what makes ENTITY fast.

Each arm now logs the CPU mask the kernel actually applied, so a silently failed `sched_setaffinity`
cannot masquerade as "pinning earns nothing":

```bash
adb logcat -s ai-chat | grep "effective cpus"
```

## 2. KleidiAI was never running

**Arm's KleidiAI registers matmul kernels for exactly two GGML types: `Q4_0` and `Q8_0`.** Every
other type — including the whole K-quant and IQ family — falls back to generic ggml, regardless of
which of the seven CPU backend variants was loaded at startup.

**Every benchmark ENTITY published before this release used Q3_K_L.** Arm's kernels never executed
once, in a project built around them. The model-info card printed "KleidiAI" anyway.

Same phone, same 512-token prompt, same four-thread unpinned config — only the quantization differs:

| | Q3_K_L (733 MB, generic ggml) | Q4_0 (773 MB, KleidiAI) | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** | **+183%** |
| Time to first token | 12,050 ms | **4,299 ms** | **−64%** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s | −13% |

The asymmetry is exactly what the hardware predicts, which is why it is trustworthy: prompt eval is
a compute-bound GEMM (what KleidiAI accelerates), while decode is memory-bandwidth-bound and tracks
bytes-per-weight rather than kernel quality — so the ~6% larger Q4_0 is slightly slower there.

Q4_0 is a quality tradeoff as well as a speed one, so ENTITY **recommends rather than switches**:
`FileType.kleidiAiAccelerated` gates the claim, and the model-info card reports whether your model
can reach Arm's kernels and what it costs when it cannot.

## 3. Widening prompt processing to all cores was a regression

Auto used split thread pools to hand prompt evaluation every online core, on the assumption that a
compute-bound phase wants all the hardware available.

That assumption was wrong. An A55 is roughly a third of an A78's throughput, so the widened pool
finished its share late and every GEMM waited on the stragglers:

| Prompt throughput, 1B Q4_0, ENTITY Auto | tok/s |
|---|---:|
| Widened to all 8 cores (v2.0.0) | 86 |
| On the 4 fast-core threads (v2.1.0) | **135** |

Both phases now run on the fast-core thread count.

## What the user actually gets

Llama-3.2-1B, ENTITY Auto, CMF Phone 1, unplugged:

| | v2.0.0 | v2.1.0 |
|---|---:|---:|
| Prompt throughput | 38.3 tok/s | **133 tok/s** |
| **Time to first token** | **13,440 ms** | **3,918 ms** |
| Decode throughput | 16.7 tok/s | 14.7 tok/s |
| Energy efficiency | 3.9 tok/W | 3.5 tok/W |

**Time-to-first-token — the latency a user actually feels on a long prompt — improves 3.4×.** Decode
gives up about 12%, the bandwidth cost of the larger quantization. The benchmark screen shows both
sides of that trade rather than hiding it.

## Fixed: the CSV export was silently writing empty files

Every benchmark export produced a **0-byte CSV** and a "CSV exported" toast.

The system file picker comes to the foreground while a multi-gigabyte model is resident. Android
kills the activity behind it. The recreated instance had no result, so `buildCsv(lastResult ?: return)`
returned early — while DocumentsUI had already created the destination file.

This is why the repo's own docs said the v2.0.0 per-pass CSVs "were not retained": not carelessness,
a defect that destroyed the evidence every single time. The CSV is now staged to cache *before* the
picker opens and carried through `onSaveInstanceState`; a lost result reports an error rather than
writing nothing.

Also fixed: `buildFeatures.buildConfig` was never enabled, so `BenchmarkActivity`'s use of
`BuildConfig.VERSION_NAME` meant the app did not compile at all under AGP 8.

## Verification

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL**; installed and benchmarked on the
  reference device (Nothing A015 / CMF Phone 1, MT6878, Android 16).
- Six three-arm benchmark runs across Llama-3.2-1B Q3_K_L, 1B Q4_0 and 3B Q4_0.
- Per-run results: [`benchmarks/device-result-template.csv`](../benchmarks/device-result-template.csv).
- Charts: [`benchmarks/plots/`](../benchmarks/plots/), regenerate with `python3 benchmarks/plot_results.py`.
