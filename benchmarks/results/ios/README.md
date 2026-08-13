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

An ONNX Runtime 1.27.0 English→Hindi translation build: int8 encoder and decoder, 285 MB of
staged weights, SHA-256 recorded per model file in the JSON. 30 iterations, counterbalanced,
5 warmup rounds, two sentence lengths, with p95/p99 and stddev.

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

## `synthetic-ablation/` — not evidence, kept for completeness

Produced by the SwiftUI app in [`../../../ios/`](../../../ios/), whose workload is an
Accelerate/vForce proxy shaped like a PP512/TG128 pass. **No model is loaded and no
inference happens.**

Two columns in these files must never be quoted:

- `power_w_est` is a hardcoded constant per arm — 4.00 / 2.20 / 3.10 / 1.15 W — identical on
  every device and in every run. iOS does not expose instantaneous power to an app.
- `tok_per_watt` is therefore decode throughput divided by that constant, and says nothing
  the decode column does not already say. `app_cpu_pct` is likewise derived from thread
  count, not sampled.

The decode and prompt columns are real timings of a real (synthetic) workload, and they show
something worth stating plainly: **the naive 8-thread arm is the fastest on all three
iPhones**, by 40–70%. That is the reverse of every Android result in this repository.

It is not a refutation of the Android finding. It is a different workload on a platform
where the app cannot place threads, so "naive" and "auto" differ only in thread count and
the OS scheduler is left to do what it was going to do anyway. It is recorded here because
publishing only the results that agree with you is how the July energy claim survived three
months longer than it deserved.

## Provenance

The iPhone 17 Pro Max runs were contributed by a collaborator; the other two devices were
run by the repository owner. The 17 Pro Max synthetic file arrived as two pasted runs and
was reformatted into a single CSV with a `run` column — values are unaltered, and the
original chat timestamps were 11:55 and 11:56 on 13 August 2026.
