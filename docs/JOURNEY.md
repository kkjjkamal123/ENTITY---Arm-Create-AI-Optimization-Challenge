# The journey - what we believed, what broke it, what it cost

Most of this project's real progress came from being wrong in a way that was measurable. This file
is the honest record of that: every claim that had to be withdrawn, why it survived as long as it
did, and what replaced it.

It exists because the falsifications are the most useful thing here. A number that was never
challenged is a number nobody has reason to trust. Each section below follows the same shape -
**the belief**, **what broke it**, **what it cost**, **what replaced it**.

---

## 1. "Pinning to the big cores earns +121%"

**The belief.** v2.0.0 measured a large decode gain after adding `sched_setaffinity` and credited
it to core affinity. It was the project's headline.

**What broke it.** A three-arm ablation. Adding a middle arm - the tuned thread count with affinity
switched *off* - showed the thread count was carrying almost all of it. Affinity was a small
residue, and on some devices a negative one.

**What it cost.** The headline claim of the first real release, and a rewrite of the benchmark to
have three arms instead of two.

**What replaced it.** The naive / threads-only / optimized ablation that everything since has used,
and a rule the project has kept: **an optimization that has not been isolated has not been
measured.** Two configurations that differ in two ways cannot attribute anything.

---

## 2. "Prompt processing is compute-bound, so give it every core"

**The belief.** Prefill parallelises better than decode, so widen it to all eight cores through a
separate ggml thread pool. Textbook, and wrong here.

**What broke it.** Measurement on the reference Dimensity 7300: PP 512 on Llama-3.2-1B-Q4_0 runs at
**116 tok/s on 4 threads and 86 tok/s across all 8.** An A55 is roughly a third of an A78, so the
widened pool finished its share late and every GEMM waited on the stragglers.

**What it cost.** A feature that had shipped and been described as an optimization was removed.

**What replaced it.** `n_pp = n_gen`. Which fixed the 4+4 case and quietly created §5 below.

---

## 3. "Q4_K falls back to generic ggml"

**The belief.** Only Q4_0 and Q8_0 reach Arm-optimized kernels; K-quants fall back to scalar code.

**What broke it.** Reading the ggml source rather than repeating the claim. Q4_K, Q5_K and Q6_K do
have Arm dotprod repacking GEMM paths. The real gap is narrower and more specific: i8mm/SMMLA.

**What it cost.** A documentation claim that was flattering and false. It had propagated into
several files.

**What replaced it.** [`KLEIDIAI-QUANTS.md`](KLEIDIAI-QUANTS.md), which states per quantization what
actually reaches which kernel - and marks the i8mm question as untestable on the hardware available
at the time rather than guessing.

---

## 4. "The benchmark measures what the app ships"

**The belief.** Self-evident. The benchmark called the same engine.

**What broke it.** A Galaxy S26 Ultra export from a stranger. `BenchmarkActivity.autoGenThreads()`
had *restated* the topology rule as `availableProcessors() - 2` instead of calling it. When the
clamp moved from 4 to 6 the two drifted. On a 2+6 flagship the native side derived 2 threads while
the benchmark's copy returned 6 - so the threads-only arm ran six unpinned threads against an Auto
arm running two. Two variables between arms: the exact attribution error the three-arm design
exists to prevent.

**What it cost.** Every CSV that device produced recorded the wrong thread count for both arms.

**What replaced it.** One definition, called from both places. And a habit: **a rule that is
restated will drift; a rule that is called cannot.**

---

## 5. "A flagship will thread wider than a 4+4 phone"

**The belief.** Written into `OPTIMIZATIONS.md` as an explicit prediction: on a device with more
than four performance cores, the derivation "counts the larger top frequency cluster, yielding 5 or
6 generation threads."

**What broke it.** Contributed benchmarks from four SoCs nobody here owns. The derivation did the
*opposite*. It counted cores within 10% of the fastest **clock**, and every modern flagship puts
its prime core 17-20% above its own big cluster - so the count collapsed to **1**, and only
`N_THREADS_MIN` pulled it back to 2.

| device | top clock | 0.9 x top | cores passing |
|---|---|---|---|
| Dimensity 7300 | 2500 MHz | 2250 | 4 |
| Tensor G5 | 3782 MHz | 3404 | **1** |
| SM8550 | 3360 MHz | 3024 | **1** |
| SM8450 | 2995 MHz | 2696 | **1** |

Because §2 had made prefill inherit that number, **prompt processing was running on two threads on
every flagship.** The symptom, visible only once the dataset existed: a Dimensity 7300 prefills at
**139 tok/s** while an SM8550 - stronger silicon, with i8mm - manages **111**.

**Why it survived so long.** Both development phones were immune, for a reason worth remembering.
The CMF is a flat 4+4. The OPPO's prime sits only 4.3% above its big cluster. Both are inside the
10% window. **The bug was invisible on every device the author owned.**

**What it cost.** A published prediction had to be rewritten as a falsification, and prefill on
every flagship had been degraded for several releases.

**What replaced it.** Decode and prefill now derive *different* widths from *different* signals -
`cpu_capacity` for the performance cluster, clock for the narrow decode set. And the decode rule was
deliberately **left alone**, because it is the only one validated against devices actually measured.
See [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md) §1-2.

---

## 6. "Pinning earns energy"

**The belief.** The optimized arm showed 2.4x-3.4x better tokens per watt than naive. Energy
efficiency was the project's moat, and pinning looked like the thing earning it.

**What broke it.** Isolating the right axis. Optimized-vs-naive changes *two* things. The comparison
that isolates pinning is threads-only vs optimized - same thread count, affinity the only
difference. On that axis:

**median +0.6% decode, -1.5% tokens per watt, positive on only 3 of 6 rows.**

The Pixel 10 is the clean demonstration: pinning is **+29.3% faster** and draws **+33.5% more
power**, so tok/W *falls* 3.2%.

**What it cost.** The energy story had to be re-attributed from pinning to thread count - the same
correction as §1, on a different metric, five months later.

**What replaced it.** Core placement became a **user setting** rather than an assumption, and the
app now reports the measurement from the user's own phone instead of asserting a default. Plotted
in `../benchmarks/plots/contributed_multidevice.png`.

---

## 7. "No wattage is showing on this phone"

**The belief.** A missing power reading meant the device did not expose battery current.

**What broke it.** It was exposing it. An OPPO CPH2737 reported **2.7 microwatts** of decode and
**11 million tokens per watt** - not missing, absurd. And the cause was not where anyone would look:
`PowerMath` had a careful heuristic for the known microamp/milliamp OEM ambiguity, and that
heuristic was fine. The **voltage** was wrong. `EXTRA_VOLTAGE` is documented in millivolts and that
device reports whole volts, so both candidate wattages fell below the plausible floor, the
heuristic gave up, and it returned the documented unit. Two independent 1000x errors compounding
into 1e6.

**What it cost.** Six contributed rows with unusable power. Two of them had been marked
`power_valid = true`, so the "only trustworthy power" view was serving nonsense.

**What replaced it.** `normalizeVoltageMv()` resolves the voltage unit *before* the current unit,
and a rule: **when a derived value is absurd, check every input's unit, not just the suspicious
one.** A plausibility heuristic is only as good as what you hand it, and it fails silently.

---

## 8. "The device is a Snapdragon"

Small, but it belongs here. The phone in §7 was reported as a Snapdragon. It reports `mt6897` - a
MediaTek Dimensity 8300. The bug it exposed is an OEM kernel unit quirk, so attributing it to the
wrong vendor would have sent the next person looking in entirely the wrong place.

**Read the device's own report, not the spec sheet you remember.**

---

## 9. "More KleidiAI coverage will be faster"

**The belief.** §4 established that KleidiAI only serves Q4_0 and Q8_0. Reading the tensor tables
instead of the `file_type` label showed that the shipped `Llama-3.2-1B-Instruct-Q4_0.gguf` is only
**76% Q4_0** - `token_embd.weight` is Q6_K, two `ffn_down` tensors are Q4_1. Because Llama 3.2 ties
its embeddings, that Q6_K tensor is also the output projection, the largest matmul in the model. A
quarter of the weights, including the biggest one, never reach the kernels the quantization was
chosen for. Obviously worth fixing.

**What broke it.** Rebuilding with `--token-embedding-type q8_0` - still an eligible type - took
coverage to 97% for 8.2% more file and no quality change. The prediction was written down first:
prefill faster (compute-bound GEMM, which is what KleidiAI accelerates), decode slower (bandwidth-
bound, and the tensor got ~30% heavier). Measured on the reference phone, three reps, pinned:
prefill **125.69 to 121.84 tok/s** and decode **17.99 to 15.94**. Slower on both.

**What it cost.** A day, and a recommendation that would have shipped a worse model to every user
if the prediction had been trusted instead of tested.

**What replaced it.** llama.cpp computes logits only for the last position of a prompt, so the
output projection runs once per prefill rather than once per token - promoting it buys nothing
there. In decode it does run every token, and decode is bandwidth-bound, so a bigger tensor is a
straight loss. **Coverage is not throughput.** The 24% figure is true and is not costing this model
any speed. The rebuilt file is published as a negative result and kept out of the catalog.

What survives is narrower: the app now reports the measured fraction rather than asserting a
boolean from a filename. Full record in
[`QUANTIZATION-QUALITY.md`](QUANTIZATION-QUALITY.md).

---

## 10. "The device probe's anchor constants can be reasoned about"

**The belief.** The model-free probe predicts decode and prefill from two microbenchmarks scaled
against one calibration device. Scaling needs that device's own readings as denominators. Two of
them were filled in by reasoning rather than by running the probe: memory bandwidth was set to
**6.4 GB/s**, derived from the throughput decode actually achieves there (0.773 GB x 18.2 tok/s
= 14 GB/s, discounted), and the integer-throughput divisor to **2.6 ops/ns**, from what four
Cortex-A78s at 2.5 GHz ought to sustain. Both are defensible numbers. Neither was measured.

**What broke it.** Running the probe on the anchor phone. It reads **26.2 GB/s** - 4.1x the
assumed figure - because a linear `arraycopy` sees near-peak DRAM throughput while decode walks
many separate tensors and pays for it. The gap is real; the mistake was putting the decode-side
number where the probe-side number belongs. The compute divisor was wrong the other way: inverting
a measured 3B time-to-first-token showed the anchor scoring **0.40** where it must score 1.0, so
the true rate is **1.042 ops/ns**, not 2.6. A dependency-chained scalar MAC loop does not run at
anything like a core's peak integer throughput.

The two errors did not cancel. Decode estimates came out 4.1x too fast and prefill 2.5x too slow.
On screen a 360M model was projected at ~251 tok/s, which is what made it obvious.

**What it cost.** Nothing shipped - the absurd number was visible the first time the probe ran on
hardware. But the feature was complete, tested and reviewed in that state, and 72 unit tests passed
against it, because the tests assert internal consistency and both constants were consistent with
themselves.

**What replaced it.** Both constants now carry the reading they came from and a note that they are
the probe's own output on a known device, not a datasheet or a derivation. The rule underneath is
narrow and worth stating: **a ratio is only dimensionless if both sides were measured the same
way.** Substituting a physically-related quantity for one side silently rescales everything
downstream, and no amount of self-consistency testing can see it.

The same review corrected a second assumption in that feature: fit was judged against installed
RAM, on the reasoning that free RAM swings minute to minute. True, but the swing is the point - a
6 GB phone with the browser and a few apps resident has well under 2 GB to give, and sizing against
6 GB recommends models into memory that is already spoken for. Fit now uses what the system reports
available, with thresholds re-derived rather than reused, since fractions calibrated against total
would reject models that do run.

---

## What actually generalised

After all of the above, the findings that survived contact with silicon nobody here owns:

- **Thread-count tuning is the lever.** It pays on every SoC measured - now nine of them, 1.34x to
  4.25x. It is the only thing in this project that has never regressed on a new device, and it
  holds at both ends: on SVE2 flagship silicon and on an all-Cortex-A53 Helio G37 with no Arm ISA
  extensions whatsoever.
- **Pinning is device-dependent in both speed and energy**, and the sign cannot be predicted from
  the spec sheet. Across the 15 rows that isolate it the median is +0.7% decode, but the range runs
  -8.5% to +29.3%, and both extremes are real: the Pixel 10 buys +29.3% decode for +33.5% power
  (tok/W falls), while the Dimensity 7300 on app 2.1.1 gets +24.0% decode for 7.9% *less* power
  (tok/W rises 34.4%). Correctly a setting, not a default - and the earlier "roughly neutral-to-
  negative on energy" summary in this file was itself too confident, corrected here on the wider
  dataset.
- **The two inference phases want different thread widths**, and conflating them costs prefill
  badly on any chip with a prime core.
- **Frequency cannot rank cores.** `cpu_capacity` can. An A55 at 2.0 GHz and an A78 at 2.5 GHz are
  25% apart in clock and roughly 3x apart in throughput.

## The method, stated once

Every correction above came from the same four habits, and they are cheaper than the mistakes:

1. **Isolate before attributing.** Two configurations differing in two ways explain nothing.
2. **Call the rule, never restate it.** Restated rules drift silently.
3. **Measure on silicon you do not own.** Two phones cannot falsify a claim about Arm.
4. **Publish the falsification, not just the fix.** A project that only records its wins is
   indistinguishable from one that never checked.

The open questions are kept in the same spirit - see
[`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md). The current one: decode
width sits on the `N_THREADS_MIN` floor on prime-core flagships, and nobody has swept it there. It
is written down precisely so it can become §10.

§9 is the newest and the cleanest example of habit 4. The coverage fix was obviously correct, cost
nothing in quality, and would have shipped on reasoning alone. Measuring it took an afternoon and
turned it into a negative result. Nothing else here would have caught it.
