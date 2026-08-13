# iOS raw exports — 13 August 2026

Three iPhones, two different apps. The distinction between them is the whole point of this
directory, so it comes first.

| Device | SoC | Real inference (`onnx/`) | Synthetic proxy (`synthetic-ablation/`) |
|---|---|---|---|
| iPhone 17 Pro Max | A19 Pro | yes | yes, two runs |
| iPhone 16 Pro Max | A18 Pro | — | yes |
| iPhone 16 | A18 | yes | yes |

Device names were resolved from the identifier each device reported for itself:
`iPhone18,2` is the iPhone 17 Pro Max and `iPhone17,3` is the base iPhone 16. The 16 Pro
Max (`iPhone17,2`) never ran the ONNX build, so it appears only in the synthetic set.

## `onnx/` — real measurements, usable as evidence

An ONNX Runtime 1.27.0 English→Hindi translation build: int8 encoder and decoder,
285,230,912 bytes of staged weights, SHA-256 recorded per model file in the JSON. 30
iterations, counterbalanced, 5 warmup rounds, two sentence lengths, with p95/p99 and stddev.

These are the files to quote. What they establish:

- **Core affinity is unavailable.** `"affinityAvailable": false` on both devices, and the
  runtime policy settles on `threads=1`.
- **More threads is slower, on both devices.** Long-sentence mean, 1 thread against 2:

  | Device | 1 thread | 2 threads | cost of the second thread |
  |---|---:|---:|---:|
  | iPhone 16 | 168.8 ms | 197.1 ms | +16.8% |
  | iPhone 17 Pro Max | 179.3 ms | 199.7 ms | +11.4% |

  On Android, thread count is the single largest earner in the whole project. Here raising
  it is a straight loss, twice.
- **KleidiAI is real and modest**, and larger on the older chip: 1.089× on the iPhone 16
  (173.2 ms on, 188.6 off), 1.045× on the 17 Pro Max (179.6 on, 187.7 off).
- **The ISA is ahead of the Android fleet**: NEON, fp16, bf16, dotprod, i8mm, SME and SME2
  all present on ARMv9. `hw.optional.arm.FEAT_SVE` is not queryable.
- **A newer Pro Max is not faster.** The base iPhone 16 returns 76.68 tok/s against the
  17 Pro Max's 74.01, and a long-sentence median of 163.9 ms against 172.0 ms — outside both
  stddevs. The A19 Pro wins the encoder stage (4.91 ms against 5.48 ms) and loses end to end.
- **But the Pro Max holds its clock and the iPhone 16 does not.** Over six sustained
  windows the 16 drifts 175.5 → 194.5 ms (1.108× degradation, thermal `nominal` → `fair`)
  while the 17 Pro Max sits at 186.2 → 186.5 ms (1.002×, flat). The cheaper phone starts
  faster and ends slower. Peak throughput and sustained throughput are different products,
  and only one of them is on the spec sheet.

Each JSON also carries an `unavailable` list naming exactly what the platform refused:
per-core frequency, thermal zone temperatures, battery current, charge counter, process CPU
ticks, migrations. That list is not a gap in the harness; it is the measurement.

### Read the right tokens-per-second column

The CSVs carry two, and the canonically-named one is **not** the figure quoted above:

| Column | iPhone 16 | iPhone 17 Pro Max | What it is |
|---|---:|---:|---|
| `inference.tokensPerSec` | 287.887 | 277.728 | the JSON's `tokensPerSecAndroidSuiteCompatible`, normalised to a fixed 10-token count so the Android suite's schema can ingest it |
| `ios.tokensPerSecCorrect` | **76.683** | **74.013** | actual generated tokens ÷ elapsed time |

Everything in this repository quotes `ios.tokensPerSecCorrect`. A reader or tool that
consumes `inference.tokensPerSec` because of its name gets a number 3.75× too high. The
column ordering is inherited from the Android export schema and was not renamed; this note
is the warning.

## `synthetic-ablation/` — not evidence, kept for completeness

Produced by the SwiftUI app in [`../../../ios/`](../../../ios/), whose workload is an
Accelerate/vForce proxy shaped like a PP512/TG128 pass. **No model is loaded and no
inference happens.**

### The arms

iOS has no core-pinning API, so the port substitutes QoS class for affinity. All three
phones report 6 cores, so `activeProcessorCount` is 6 throughout:

| Arm | Workers | QoS | Isolates |
|---|---:|---|---|
| `Naive` | 6 | `.default` | — the baseline |
| `Threads Only` | 3 | `.default` | thread count, against `Naive` |
| `Entity Auto` | 3 | `.userInteractive` | QoS placement, against `Threads Only` |
| `Efficiency` | 3 | `.background` | E-core confinement |

That is a real ablation with the same shape as the Android one: `Naive → Threads Only`
changes only the worker count, `Threads Only → Entity Auto` changes only the scheduler
priority.

### Three columns that must never be quoted

- **`power_w_est` is computed, not measured.** `BenchmarkEngine.swift` evaluates
  `0.4 + workers × perThreadWatts`, with `perThreadWatts` fixed per arm (0.6 naive and
  threads-only, 0.9 auto, 0.25 efficiency). That is what produces 4.00 / 2.20 / 3.10 / 1.15 W
  here — it is identical across these three files only because all three devices have 6
  cores. On an 8-core device the naive arm would read 5.20 W. iOS exposes no instantaneous
  power to an application, so there is nothing underneath this number.
- **`tok_per_watt` is therefore `decode_tok_s ÷ that estimate`** and says nothing the decode
  column does not already say.
- **`app_cpu_pct` is `min(cores × 100, busy-thread-seconds ÷ wall-time × 100)`** — bounded by
  worker count rather than sampled from the OS. It reads 600 for the 6-worker arm and 300
  for the 3-worker arms because the workload keeps every worker busy.

Also note **`decode_stddev` and `prompt_stddev` are not sample standard deviations.** The
engine sums squared deviations about the *median* rather than the mean and divides by `n`
rather than `n − 1`, with `n = 3`. Squared deviation is minimised at the mean, so these
values are systematically inflated. Treat them as a spread indicator, not a statistic.

### What the decode column does show

The decode and prompt timings are real measurements of a real (synthetic) workload, and the
ordering is worth stating plainly: **the 6-worker naive arm is the fastest on all three
iPhones**, by 37% to 73%.

| Device | Naive (6w) | Threads Only (3w) | Entity Auto (3w, high QoS) |
|---|---:|---:|---:|
| iPhone 17 Pro Max, run 1 | 362.10 | 209.69 (**−42%**) | 214.16 |
| iPhone 17 Pro Max, run 2 | 354.56 | 214.88 | 211.42 |
| iPhone 16 Pro Max | 387.55 | 283.37 (**−27%**) | 283.55 |
| iPhone 16 | 306.39 | 207.35 (**−32%**) | 203.45 |

Two readings, and both are the mirror image of the Android result:

1. **Halving the worker count costs 27–42%.** On Android, cutting 8 threads to 4 is the
   single largest earner in this project, because the discarded threads were landing on
   Cortex-A55s that gated every step. Here the cores are 2P+4E and there is no way to say
   *which* four to keep — so dropping to 3 workers just discards throughput.
2. **QoS buys essentially nothing.** `Threads Only → Entity Auto` changes only the scheduler
   priority, and moves decode by +2.1% and −1.6% across the two iPhone 17 Pro Max runs,
   +0.1% on the 16 Pro Max and −1.9% on the 16 — a band that straddles zero and sits inside
   the run-to-run spread. The one lever iOS offers in place of affinity does not measurably
   place anything.

Neither reading refutes the Android finding. This is a different workload on a platform
where thread placement cannot be expressed, measured by an instrument whose power column is
an estimate. It is recorded here because publishing only the results that agree with you is
how the July energy claim survived three months longer than it deserved.

## Provenance

The iPhone 17 Pro Max runs were contributed by a collaborator; the other two devices were
run by the repository owner. The 17 Pro Max synthetic file arrived as two pasted runs and
was reformatted into a single CSV with a `run` column — values are unaltered, and the
original chat timestamps were 11:55 and 11:56 on 13 August 2026.
