# ENTITY benchmark record

This is the one canonical benchmark document for ENTITY. It reports the benchmark path that ships
in the Android app. The public source is [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

**The headline finding is not the one this project started with.** ENTITY's own ablation
disproved its flagship claim: the +121% decode figure published for v2.0.0 was credited to
big-core affinity pinning, and it is not the pinning — the multiplier comes from using four
threads instead of eight. The current benchmark of record is the pair of five-run four-arm
exports below (ENTITY Bench v1.1.0, 2026-07-18, two devices, raw CSVs retained). They sharpen
the attribution: **the thread count earns the multiplier on every device; what the pinning adds
is device-dependent** — +21% decode on the Dimensity 7300, +1% decode but markedly lower power on
the Snapdragon 6 Gen 4 — and LITTLE-cluster pinning loses on both axes on both phones.

## What was measured

The in-app benchmark runs a synthetic llama-bench workload of PP 512 and TG 128 on the loaded
model. Each configuration runs on an unplugged phone, with a thermal cooldown before every pass.
Values are median (± population standard deviation where more than one run was taken).

| Arm | Configuration |
|---|---|
| Naïve | Eight threads across all online CPU cores. The out-of-the-box default. |
| Threads only | The same thread count Auto derives, with affinity off: no `sched_setaffinity`, no pinned thread pool, placement left to the Linux scheduler. This is what an upstream llama.cpp `-t N` run does. |
| ENTITY Auto | CPU cores are ranked by maximum frequency; both phases run on the fastest two to four cores. |
| Efficiency (Bench only) | Auto's thread count pinned to the LITTLE cluster instead. Answers a tok/W question — are the little cores energy-efficient for decode, or only slow? |

### Why three arms

Naïve and Auto differ in two variables at once: thread count and core placement. A two-arm result
cannot say which one earns the speed-up. The threads-only arm holds the thread count at Auto's
value and removes only the affinity, so:

- naïve → threads-only isolates **the thread count**
- threads-only → Auto isolates **the core pinning**

Both the decode and prompt rows are clean ablations, because every arm now runs both phases on the
same thread count. The app prints the split under its own results table.

Each arm logs the CPU mask the kernel actually applied (`effective cpus` in logcat), so a failed
`sched_setaffinity` cannot masquerade as "pinning earns nothing". See
[REPRODUCIBILITY](REPRODUCIBILITY.md).

## The current result: four-arm exports (ENTITY Bench v1.1.0, 2026-07-18)

The standalone [ENTITY Bench](../apk/ENTITY-Bench-v1.2.1-release.apk) app runs a fourth
arm the chat app's three-arm ablation never had (these exports were taken with v1.1.0): **efficiency** — the same four threads as Auto,
but pinned to the LITTLE cluster instead of the performance cluster. It exists to measure what the
slow cores can and cannot do, so the affinity policy is chosen from data rather than assumption.

Two exports were taken on 2026-07-18, Llama-3.2-1B Q4_0, PP 512 / TG 128, unplugged, five runs per
arm. Both raw CSVs are retained in [`results/`](results/). Values are median ± population standard
deviation over the five runs.

**CMF Phone 1 (Nothing A015), Dimensity 7300, start 31 °C:**

| Arm | Decode (tok/s) | Prompt (tok/s) | TTFT (ms) | Power (W) | Efficiency (tok/W) |
|---|---:|---:|---:|---:|---:|
| Naïve (8 thr, all cores) | 10.8 ± 1.3 | 111 ± 13.6 | 4,720 | 4.25 ± 0.12 | 2.61 ± 0.33 |
| Threads only (4 thr, no pin) | 15.0 ± 0.5 | 137 ± 1.4 | 3,803 | 4.09 ± 0.17 | 3.66 ± 0.24 |
| ENTITY Auto (4 thr, perf-pinned) | **18.1 ± 0.4** | **139 ± 0.8** | **3,739** | 3.96 ± 0.07 | 4.59 ± 0.17 |
| Efficiency (4 thr, LITTLE-pinned) | 15.0 ± 0.3 | 82.5 ± 0.2 | 6,272 | **3.51 ± 0.02** | 4.28 ± 0.09 |

**OPPO CPH2729, Snapdragon 6 Gen 4, start 35.3 °C:**

| Arm | Decode (tok/s) | Prompt (tok/s) | TTFT (ms) | Power (W) | Efficiency (tok/W) |
|---|---:|---:|---:|---:|---:|
| Naïve (8 thr, all cores) | 9.7 ± 0.5 | 152 ± 4.5 | 3,473 | 2.87 ± 1.04 | 3.37 ± 1.05 |
| Threads only (4 thr, no pin) | 17.4 ± 0.3 | 129 ± 22.4 | 4,026 | 2.52 ± 0.90 | 6.80 ± 1.82 |
| ENTITY Auto (4 thr, perf-pinned) | **17.5 ± 0.2** | 129 ± 23.1 | 4,026 | **1.78 ± 0.71** | **9.85 ± 2.54** |
| Efficiency (4 thr, LITTLE-pinned) | 14.3 ± 0.1 | 127 ± 1.9 | 4,101 | 3.06 ± 0.96 | 4.74 ± 1.57 |

![Four-arm decode and efficiency](plots/four_arm_decode_20260718.png)

Regenerate the figure with:

```bash
python3 benchmarks/plot_four_arm.py
```

### What the efficiency arm shows

Pinning the four threads to the LITTLE cluster is a latency-for-power trade, and the two SoCs take
it differently. On the CMF Phone the A55s hold decode at the four-thread level (15.0 tok/s, matching
threads-only, because decode is memory-bound and the little cores clock to 2.0 GHz) but **collapse
prompt throughput from 139 to 82.5 tok/s and push TTFT from 3.7 s to 6.3 s** — prompt eval is the
compute-bound GEMM the A55s are worst at. In exchange it draws the least power of any arm (3.51 W).
On the OPPO the prompt holds up (127 vs 129 tok/s) while decode gives up more (14.3 vs 17.5), so the
same policy lands in a different place. The efficiency arm is therefore a real option to expose, not
a default — exactly the case for shipping the benchmark rather than a hardcoded mask.

### The CMF export disagrees on pinning

On the CMF Phone this export does something no earlier run did. The per-run decode figures are:

| Arm | Decode, per run (tok/s) | Median | Range |
|---|---|---:|---:|
| Threads only | 15.0, 14.7, 15.2, 15.9, 14.5 | 15.0 | 14.5 – 15.9 |
| ENTITY Auto | 17.5, 17.5, 18.3, 18.4, 18.1 | 18.1 | 17.5 – 18.4 |

Auto's slowest run (17.5) is faster than threads-only's fastest (15.9): the two distributions **do
not overlap**, and pinning measures **+20.7%** on the medians with tight spreads on both arms. Every
prior three-run set had these two arms overlapping with pinning inside ±2% — see
[What the three-run export says about the pinning](#what-the-three-run-export-says-about-the-pinning).

The OPPO export taken the same day still reads +0.6% (17.4 → 17.5), inside the noise, consistent
with every earlier run.

**What this establishes.** These five-run exports carry more resolution than any earlier set —
five runs per arm instead of three, tight spreads, and per-run raw CSVs — and they replace
"pinning earns nothing" with a device-dependent statement: on the Dimensity 7300 pinning buys
decode (+21%, distributions non-overlapping) and +25% tok/W; on the Snapdragon 6 Gen 4 it buys
power (2.52 → 1.78 W median, tok/W 6.80 → 9.85 on medians, with the wide spreads this SoC's noisy
battery telemetry produces) while decode stays inside the noise. On both phones the LITTLE-pinned
arm is strictly worse than perf-pinning on speed *and* tok/W, so the efficiency cores are not an
efficiency win for LLM inference — measured, not assumed. The July three-run record on the same
CMF phone read pinning at ~0% ([Result 1](#result-1-the-thread-count-earns-the-multiplier)); that
history is retained below, and the difference between the chat app's three-run bench then and the
standalone five-run bench now is an open question the raw CSVs keep answerable.

*Update (v3.0.1, 2026-07-20):* part of that difference now has a mechanism. Through chat app
v3.0.0, any generation with the live metrics graph or stats bar visible paid a per-token
main-thread tax — three binder IPCs (battery intent, current draw, memory info) plus a
seven-series graph redraw per token — competing with the decode threads pinned to the big cores;
measured in-chat cost was ~18 → ~14 tok/s on the CMF. The standalone bench app never had this
path, which is one reason its numbers ran higher than in-chat readings. v3.0.1 samples metrics on
a fixed 500 ms clock, closing most of that gap.

## Result 1: the thread count earns the multiplier

CMF Phone 1 (Nothing A015), MediaTek Dimensity 7300, 4× Cortex-A78 + 4× Cortex-A55, Android 16.
Decode throughput, tokens/s:

| Model | Naïve (8 thr) | Threads only (4 thr, no pin) | ENTITY Auto (4 thr, pinned) | Thread count earns | Pinning earns |
|---|---:|---:|---:|---:|---:|
| Llama-3.2-1B Q3_K_L (3 runs) | 8.8 ± 0.50 | 16.9 ± 0.08 | 16.7 ± 1.3 | **+92%** | **−1%** |
| Llama-3.2-1B Q4_0 (1 run) | 7.9 | 14.7 | 14.7 | **+86%** | **+0%** |
| Llama-3.2-1B Q4_0 (3 runs, 2026-07-15 midday) | 7.7 ± 0.78 | 15.9 ± 0.22 | 16.0 ± 2.1 | **+106%** | +1% |
| Llama-3.2-1B Q4_0 (3 runs, 2026-07-15 evening) | 8.6 ± 0.82 | 15.9 ± 1.58 | 15.9 ± 0.09 | **+85%** | **+0%** |
| Llama-3.2-3B Q4_0 (1 run) | 3.1 | 6.0 | 6.8 | **+94%** | +13% |
| Llama-3.2-3B Q4_0 (1 run) | 3.5 | 6.3 | 6.3 | **+81%** | **+0%** |

![Decode attribution](plots/decode_attribution.png)

Running eight threads on a 4+4 big.LITTLE phone lets the Cortex-A55s gate every decode step. Simply
using four threads removes that, and it is most of what `llama.cpp -t 4` already gives any user who
bothers to pass the flag.

**In this July three-run record, pinning those four threads added nothing measurable.** The two 3B
runs disagree (+13% and +0%), and a third 3B run taken while charging measured −16%; single 3B runs
swing about ±15%, so the +13% is not a finding. The 2026-07-18 five-run exports above supersede
this as the current statement on pinning — +21% on this same phone, +1% on the OPPO — but the
thread-count finding is unchanged by every set ever taken: it is the universal earner.

The 2026-07-15 evening set is the sharpest statement of the pattern yet: the pinned and unpinned
medians are identical at 15.9 tok/s, but pinning collapses the spread from ±1.58 to ±0.09 tok/s.
What the pinning buys is repeatability, not speed.

The affinity code still ships, because it costs nothing and a different SoC may behave differently.
It is no longer claimed as the source of the speed-up.

> This section is the July 2026 three-run record, kept as history with its original readings.
> The current statement on what pinning earns is the five-run four-arm section
> [above](#the-current-result-four-arm-exports-entity-bench-v110-2026-07-18).

## Result 2: KleidiAI only accelerates Q4_0 and Q8_0

Arm's KleidiAI registers matmul kernels for exactly two GGML types, `Q4_0` and `Q8_0`
(`ggml/src/ggml-cpu/kleidiai/kleidiai.cpp`). Every other type — including the whole K-quant and IQ
family — falls back to generic ggml kernels, no matter which of the seven CPU backend variants was
loaded at startup.

**Every benchmark ENTITY published before v2.1.0 used Q3_K_L, so KleidiAI never executed once.**

Same phone, same 512-token prompt, same four-thread unpinned configuration — only the quantization
differs:

| | Q3_K_L (KleidiAI cannot run) | Q4_0 (KleidiAI runs) | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** | **+183%** |
| Derived TTFT (512-token prompt) | 12,050 ms | **4,299 ms** | **−64%** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s | −13% |

![KleidiAI](plots/kleidiai_prompt_ttft.png)

This is exactly what the hardware predicts, which is why it is trustworthy:

- **Prompt evaluation is a compute-bound GEMM.** It is what KleidiAI's i8mm/dotprod kernels are
  built for, so it nearly triples.
- **Decode is memory-bandwidth-bound.** It tracks bytes-per-weight, not kernel quality. Q4_0 is
  773 MB against Q3_K_L's 733 MB — about 6% more bytes — and lands about 6-13% slower. Kernel
  quality cannot help a workload that is waiting on memory.

Q4_0 is a quality tradeoff as well as a speed one, so ENTITY **recommends** rather than silently
switches: the model-info card now reports whether the loaded quantization can reach KleidiAI, and
what it costs when it cannot.

## Result 3: widening prompt processing to all cores was a regression

Until v2.1.0, Auto used split thread pools: generation on the fast cores, prompt processing widened
to *every* online core, on the assumption that prompt eval is compute-bound and therefore wants all
the hardware.

Measured, that assumption is wrong. An A55 is roughly a third of an A78's throughput, so the
widened pool finishes its share late and every GEMM waits on the stragglers:

| Prompt throughput, 1B Q4_0, ENTITY Auto | tok/s |
|---|---:|
| Prompt widened to all 8 cores (v2.0.0 behaviour) | 86 |
| Prompt on the 4 fast-core threads (v2.1.0) | **135** |

Both phases now run on the fast-core thread count. The right width is an empirical property of the
SoC, not a constant — a tri-cluster chip may prefer something else, and the in-app benchmark is what
should decide it.

## Combined effect on the user

Llama-3.2-1B, ENTITY Auto, CMF Phone 1, unplugged:

| | v2.0.0 (Q3_K_L, widened prompt pool) | v2.1.0 (Q4_0, fast-core prompt) |
|---|---:|---:|
| Prompt throughput | 38.3 tok/s | **133 tok/s** |
| **Time to first token** | **13,440 ms** | **3,918 ms** |
| Decode throughput | 16.7 tok/s | 14.7 tok/s |
| Energy efficiency | 3.9 tok/W | 3.5 tok/W |

Time-to-first-token, the latency a user actually feels on a long prompt, drops by **3.4×**. Decode
gives up about 12%, which is the bandwidth cost of the larger quantization — a deliberate trade,
and the benchmark screen shows both sides of it.

![Energy efficiency](plots/energy_efficiency.png)

## Result 4: the same work costs 42–47% less battery

Tokens-per-watt is the metric every on-device app quotes, and it undersells what is happening. The
app's CSV export records the battery current every 150 ms, so the *energy* a pass actually cost can
be integrated from the measured power curve rather than inferred.

![Energy per task](plots/energy_per_task.png)

From the 2026-07-15 evening export (first pass of each arm):

| Arm | Pass duration | Mean power | **Energy for 128 tokens** |
|---|---:|---:|---:|
| Naïve (8 threads) | 19.9 s | 4.34 W | **86 J** |
| Threads only | 12.2 s | 3.98 W | **49 J** (−44%) |
| **ENTITY Auto** | **11.8 s** | 4.22 W | **50 J** (−42%) |

The earlier 2026-07-14 single-run export measured the same shape: naïve 95 J, threads-only 57 J
(−40%), Auto 51 J (−47%). Across both exports the saving is 42–47%, and threads-only versus Auto
is inside the noise on this energy metric, as it was on July's decode medians.

**All three configurations draw roughly the same watts.** ENTITY does not win by sipping less
current — it wins because it finishes in half the time. Energy is the area under the power curve,
which is why the left panel of that figure is a literal picture of the right one.

Integrated by trapezoid from 273 battery-current samples in
[`results/entity_1b-q4_0_unplugged_3run_20260715b.csv`](results/entity_1b-q4_0_unplugged_3run_20260715b.csv):

```bash
python3 benchmarks/plot_energy.py benchmarks/results/entity_1b-q4_0_unplugged_3run_20260715b.csv
```

The script refuses to run on a charging export, because the battery current would be the charger's
rather than the workload's.

## Against other apps

ENTITY was measured against Arm's own AI Chat and PocketPal AI on the same phone, the same GGUF and
the same PP 512 / TG 128 workload. The current session is 2026-07-20: all three apps re-measured the
same day, five runs each, 30-minute cooldown between apps.

| App | Prompt (pp 512) | Token generation (tg 128) | Threads |
|---|---:|---:|---|
| PocketPal AI | 88.32 tok/s | 13.9 tok/s | 6 |
| Arm AI Chat (Arm's own app) | 121 ± 2.99 tok/s | 12.4 ± 0.0751 tok/s | not reported |
| **ENTITY** | **128 tok/s** | **18.2 tok/s** | 4, pinned |

**Against Arm's own reference app, on Arm's own silicon: +6% prompt, +47% token generation.**
Against PocketPal: +45% prompt, +31% token generation. Decode is where the thread-count policy acts
and where the margin is; the prompt column is close because all three apps run Q4_0 and therefore
all three reach the same KleidiAI kernels.

The July 2026-07-14 session is retained beside it, and the two disagree in ways worth publishing:
PocketPal and Arm swapped places on decode (Arm led 12.9 to 10.9 in July; PocketPal leads 13.9 to
12.4 now), ENTITY's prompt margin over Arm narrowed from +11% to +6%, and its decode margin widened
from +21% to +47%. PocketPal's decode swung about 27% between sessions on identical hardware while
Arm's held within about 4%, and that swing is what flipped the ranking — which is the argument for
never pairing a figure from one session with a figure from another.

Setup, both sessions, screenshots, and the caveats (including why ENTITY's live-chat readout is
*not* used): [competitor-comparison/](competitor-comparison/README.md).

## Interpretation and limits

- One phone, two models, one quantization pair. Not a universal multiplier.
- The 1B Q3_K_L row and both 2026-07-15 1B Q4_0 rows are three runs; the remaining Q4_0 rows are
  single runs, so their sd columns are 0 and not meaningful. Treat the single-run figures as
  indicative, and the ±15% swing seen across the 3B runs as the noise floor for one pass.
- The thread-count gain itself moves run to run: the two 1B Q4_0 three-run sets read +106% and
  +85%, mostly because the naïve baseline is the noisiest arm. The honest summary across every
  three-run set is "roughly 2×", not one fixed percentage.
- These are synthetic benchmark values, not live multi-turn chat speed.
- Power and tokens-per-watt are recorded only while unplugged; the app hides them while charging
  because USB input invalidates the battery-current reading.

## Historical two-arm record (v2.0.0)

These are the originally published numbers. They are correct as measurements and wrong as an
attribution: they compare naïve against Auto, which differ in both thread count and placement, so
nothing in them can be credited to core pinning.

| Device | Metric | Naïve | ENTITY Auto | Change |
|---|---|---:|---:|---:|
| CMF Phone 1, Dimensity 7300 | Decode throughput | 8.0 ± 1.1 tok/s | 17.7 ± 0.56 tok/s | +121% |
|  | Energy efficiency | 1.7 ± 0.36 tok/W | 4.2 ± 0.23 tok/W | 2.5× |
| OPPO CPH2729, Snapdragon 6 Gen 4 | Decode throughput | 6.0 ± 1.1 tok/s | 13.1 ± 0.05 tok/s | +117% |
|  | Energy efficiency | 1.8 ± 0.24 tok/W | 3.8 ± 0.31 tok/W | 2.1× |

What the cross-vendor repeat *does* prove is that the **mechanism** is SoC-agnostic: ranking cores
by live `cpufreq` rather than hardcoding a mask finds the performance cluster unchanged on MediaTek
and Qualcomm. What it does not prove is that the pinning is what produced the gain.

## Open: is the derived thread count the best one?

Every result on this page compares the shipped policy against the phone's default. None of them
asks whether the shipped policy is the *best configuration that phone can run*, because every arm
uses one thread width — the size of the top frequency cluster.

That rule cannot be right everywhere, and the reason is not subtle. It reads clock frequency, and
clock frequency does not distinguish a slow core from a narrow one:

| Device | Second-tier clock vs top | Right answer |
|---|---|---|
| CMF Phone 1 | Cortex-A55 @ 2000 vs A78 @ 2500 = **80%** | exclude — an A55 is roughly a third of an A78's throughput |
| Galaxy S26 Ultra | mid @ 3628 vs prime @ 4742 = **76%** | a different case entirely — both are performance-class |

Nearly identical ratios, and no frequency threshold separates them. Encoding a table of core part
numbers would age with every new SoC, so **ENTITY Bench v1.2.0 adds a thread sweep** instead: every
width the device can use, each pinned to that many of its fastest cores and again scheduler-placed,
with the winning configuration named. A pinned/no-pin pair at one width isolates placement while the
column isolates width.

No sweep results are published yet. When they are, they belong here, and they are what should decide
whether the derivation rule changes — not an argument from topology.

## Reproduce

Follow the [reproducibility protocol](REPRODUCIBILITY.md). In short: install the current APK, load
the model, unplug and cool the phone, choose three runs in **Benchmark**, then export the CSV. The
app runs a discarded warm-up, then naïve, threads-only and Auto in that order, with the same thermal
cooldown before every pass so the ordering cannot favour the last arm.

Machine-readable results are in [device-result-template.csv](device-result-template.csv), one row
per run, with `kleidiai_accelerated` and `pinning_decode_delta_pct` columns. Arms that were never
run are marked `not-measured`; power on a charging run is marked `not-valid-charging`. Nothing is
back-filled from another run.

Regenerate the charts on this page with:

```bash
python3 benchmarks/plot_results.py
```

### Evidence status

The per-pass CSV exports for the v2.0.0 reference runs were never retained — and that was not
carelessness. The export was **broken**: the system file picker comes to the foreground while a
multi-gigabyte model is resident, Android kills the activity behind it, and the recreated instance
had no result to write, so it returned early while the picker had already created the file. Every
export produced a 0-byte CSV *and* a "CSV exported" toast. Fixed in v2.1.0 — the CSV is now staged
to cache before the picker opens.

**Two real exports are now retained** in [`results/`](results/), the first that ever survived:

| File | Run | Contents |
|---|---|---|
| `entity_1b-q4_0_unplugged_1run_20260714.csv` | 1B Q4_0, unplugged, 1 run | 4,046 telemetry samples, 2,312 CPU-frequency samples. Power is valid; every graph on this page comes from it. |
| `entity_1b-q4_0_charging_3run_20260714.csv` | 1B Q4_0, charging, 3 runs | 12,012 telemetry samples. Speed is valid and this was the tightest three-run evidence in the project until the 2026-07-15 evening export. **Its power columns are not** — the phone was charging, so they measure the charger. Both plot scripts refuse to draw power from it. |
| `entity_1b-q4_0_unplugged_3run_20260715.csv` | 1B Q4_0, unplugged, 3 runs | 13,007 telemetry samples, app v2.2.0. The first unplugged three-run set: speed AND power both valid. Thread count +106% decode, pinning +1%. Caveat: its `affinity_naive` meta row says `pinned_fast_cores`; the naive mask is the 8 fastest of 8 cores, i.e. all of them, so the label was misleading and the CSV writer was corrected after this export. |
| `entity_1b-q4_0_unplugged_3run_20260715b.csv` | 1B Q4_0, unplugged, 3 runs | 12,126 data rows (855 telemetry samples × 14 channels), app v2.2.0, evening of the same day. The first export with the corrected `affinity_naive` label (`mask_all_cores_effectively_unpinned`). Speed and power both valid, every pass LIGHT thermal from a 36 °C start. Thread count +85% decode; pinning +0% with the tightest Auto spread recorded (±0.09 tok/s). The energy figure is drawn from it. |
| `entity_1b-q4_0_unplugged_5run_cmf_20260718.csv` | 1B Q4_0, unplugged, 5 runs | ENTITY **Bench** app v1.1.0, CMF Phone, 31 °C start. First retained four-arm export (adds the LITTLE-pinned `efficiency` arm). Five runs per arm. Thread count +39% decode; **pinning +21%, non-overlapping**. Current headline export. See [the current result](#the-current-result-four-arm-exports-entity-bench-v110-2026-07-18). |
| `entity_1b-q4_0_unplugged_5run_oppo_20260718.csv` | 1B Q4_0, unplugged, 5 runs | ENTITY **Bench** app v1.1.0, OPPO CPH2729, 35.3 °C start. First retained per-run OPPO export — supersedes the historical two-arm OPPO row, which had no raw CSV. Thread count +80% decode; pinning +1% on decode but power 2.52 → 1.78 W median (tok/W 6.80 → 9.85). Current headline export. Power columns are noisy on this SoC (naïve 1.96 – 4.72 W across runs), so the tok/W spreads are wide and real. |

Exports carry per-pass values, per-core CPU frequency samples, battery temperature, thermal state,
power, and the per-arm affinity policy.

### What the three-run export says about the pinning

`entity_1b-q4_0_charging_3run_20260714.csv` is the cleanest ablation evidence recorded so far —
three passes per arm, every one at LIGHT thermal:

| Arm | Decode, per pass (tok/s) | Median |
|---|---|---:|
| Naïve | 6.94, 7.21, 7.74 | 7.21 |
| Threads only | 16.2, 16.0, 16.1 | 16.1 |
| ENTITY Auto | 16.4, 16.6, 15.7 | 16.4 |

Thread count: **+123%**. Pinning: **+1.9%** on the median — but Auto's worst pass (15.7) falls below
threads-only's worst (16.0), so the distributions overlap and the mean-to-mean difference is +0.8%.
The pinning is somewhere between 0 and +2%, inside the run-to-run noise. That is consistent with
every other run and with this document's conclusion: **the thread count earns the gain.**

The 2026-07-15 evening export (`entity_1b-q4_0_unplugged_3run_20260715b.csv`) repeats the ablation
unplugged with valid power:

| Arm | Decode, per pass (tok/s) | Median |
|---|---|---:|
| Naïve | 8.60, 7.04, 8.92 | 8.60 |
| Threads only | 15.9, 16.5, 12.9 | 15.9 |
| ENTITY Auto | 15.9, 15.7, 15.9 | 15.9 |

Thread count: **+85%**. Pinning: **+0.0%** — the medians are identical. Threads-only's third pass
(12.9) is an outlier of the same kind the midday export saw in its Auto arm; the median is robust
to it, and the pinned arm's three passes span just 0.2 tok/s.

## Contribute a device result

The easiest path is the standalone [ENTITY Bench](../app/entity.bench.android/README.md) APK
([`apk/ENTITY-Bench-v1.2.1-release.apk`](../apk/ENTITY-Bench-v1.2.1-release.apk)). It is a dedicated
benchmark app with no chat: you import a GGUF via the file picker, run the same three-arm ablation on an
unplugged, cooled phone, and tap **Export CSV** on the result page. Every result is autosaved on the
device, so the CSV can also be exported later from the app's history. There is nothing to set up in the
chat app and no model to import twice.

The chat app's own benchmark keeps a history too as of v3.1.0, so a run made there can be re-exported
later instead of only at the moment it finishes.

Use [device-result-template.csv](device-result-template.csv) for a result from another arm64 Android
phone, and commit the raw exported CSV beside it. Keep the model, quantization, app version,
selected backend, thermal starting point, and charging state with the row. A different SoC may well
reach a different answer about the pinning — that is the point of shipping the ablation rather than
an assertion.
