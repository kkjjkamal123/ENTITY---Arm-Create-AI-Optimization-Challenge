# The contributed dataset - what it is, and what it has already changed

[`CONTRIBUTE-BACKEND.md`](CONTRIBUTE-BACKEND.md) explains how results reach the database.
This file is about the results themselves: what has arrived, what it establishes, what it
falsified, and the rules for reading it without drawing a claim it cannot support.

The dataset exists because this project's central finding is a claim about **Arm silicon**, and
two phones cannot make that claim. Everything below came from devices the author does not own.

## What is in it

As of 2026-07-25: **22 rows, 9 SoCs**, app 1.5.0 through 2.1.1.

| device | SoC | ISA flags | topology (MHz) |
|---|---|---|---|
| Nothing A015 (x6) | MediaTek MT6878 / Dimensity 7300 | dotprod, fp16 | 4x2000 + 4x2500 |
| Pixel 10 | Google Tensor G5 | dotprod, i8mm, sve, sve2, fp16 | 2x2246 + 5x3052 + 1x3782 |
| Galaxy S23 | Qualcomm SM8550 | dotprod, i8mm, fp16 | 3x2016 + 4x2803 + 1x3360 |
| Galaxy S22 Ultra | Qualcomm SM8450 | dotprod, i8mm, fp16 | 4x1785 + 3x2496 + 1x2995 |
| Galaxy S20 FE 5G | Qualcomm SM8250 | dotprod, fp16 | 4x1804 + 3x2419 + 1x2841 |
| OPPO CPH2737 (x7) | MediaTek MT6897 / Dimensity 8300 | dotprod, i8mm, sve, sve2, fp16 | 4x2200 + 3x3200 + 1x3350 |
| vivo I2301 | MediaTek MT6886 | dotprod, i8mm, sve, sve2, fp16 | 6x2000 + 2x2800 |
| OPPO CPH2553 (x3) | MediaTek MT6833 / Dimensity 700 | dotprod, fp16 | 6x2000 + 2x2203 |
| TECNO KI5q | MediaTek MT6765H / Helio G37 | **none** | 4x2301 + 4x1800 |

Six of the nine SoCs are prime-core designs, which neither development phone effectively is - and
that difference is what made the dataset immediately useful. The spread now runs from SVE2
flagship silicon down to an all-Cortex-A53 Helio G37 that reports **no ISA flags at all** - the
armv8.0 baseline path, finally exercised on real hardware rather than argued from a build matrix.

MT6886 ships under several Dimensity marketing names, so it stays raw here and on the site.

## What it establishes

**1. Thread-count tuning is the lever that generalises.** Going from the naive 8-thread
configuration to the derived thread count pays on every SoC measured, now across nine of them:
**1.34x to 4.25x**. No device has regressed, including the one with no Arm ISA extensions at all.

The four SoCs added after 2026-07-23 (best clean unplugged row each, Llama-3.2-1B-Q4_0, decode
tok/s):

| device | SoC | naive | threads-only | optimized | thread step | pin step |
|---|---|---|---|---|---|---|
| Galaxy S20 FE 5G | SM8250 | 5.34 | 22.70 | 22.80 | **4.25x** | +0.4% |
| vivo I2301 | MT6886 | 4.32 | 16.40 | 16.00 | 3.80x | -2.4% |
| OPPO CPH2553 | MT6833 | 7.75 | 13.90 | 14.00 | 1.79x | +0.7% |
| TECNO KI5q | MT6765H | 3.43 | 4.59 | 4.58 | **1.34x** | -0.2% |

The SM8250 is now the dataset's largest thread-count multiplier, and it has no i8mm. That is worth
stating plainly: the biggest win here comes from not fighting the scheduler, not from a kernel.

**2. Pinning is device-dependent in both speed and energy.** This is the finding
most at risk of being misread, because the headline optimized-vs-naive ratio *looks* like
pinning earning energy. It is not. The comparison that isolates pinning is **threads-only vs
optimized**, which run the same thread count and differ only in affinity:

| device | pinning: decode | pinning: watts | pinning: tok/W |
|---|---|---|---|
| A015 (Q8_0) | +4.9% | -5.4% | +10.9% |
| A015 (Q8_0, repeat) | -4.8% | +0.1% | -4.9% |
| Pixel 10 | **+29.3%** | **+33.5%** | **-3.2%** |
| A015 (Q4_0) | -8.5% | +7.5% | -14.9% |
| S23 | +1.7% | +1.1% | +0.2% |
| S22 Ultra | -0.5% | -2.1% | +2.2% |

The rows added since, on the same threads-only vs optimized axis:

| device | pinning: decode | pinning: watts | pinning: tok/W |
|---|---|---|---|
| A015 (Q4_0, app 2.1.1) | **+24.0%** | -7.9% | **+34.4%** |
| A015 (Q4_0, app 2.1.0) | +6.0% | -11.7% | +20.1% |
| A015 (Q4_0, app 2.0.0) | -0.6% | -3.6% | +3.0% |
| S20 FE 5G | +0.4% | -3.8% | +4.4% |
| vivo I2301 | -2.4% | +1.8% | -4.2% |
| CPH2553 | +0.7% | +3.9% | -3.1% |
| TECNO KI5q | -0.2% | -1.9% | +1.7% |
| CPH2737 (app 2.1.1) | +1.8% | *excluded - see below* | *excluded* |

Over the 15 clean rows carrying both arms the median is **+0.7% decode** (positive on 9 of 15); over
the 14 of those with usable power, the median is **+2.0% tok/W** (positive on 9 of 14).
The distribution is what matters, not the median: it runs from **-8.5% to +29.3%**, and the two
extremes are both real. The Pixel 10 is the clean cost demonstration - pinning is 29.3% faster and
draws 33.5% more power, so tokens per watt goes *down*. The A015 on app 2.1.1 is the clean benefit
demonstration - +24.0% decode while drawing 7.9% *less* power, so tok/W rises 34.4%.

So the honest statement is not "pinning earns nothing" and not "pinning earns a multiplier". It is
**device-dependent, and the sign cannot be predicted from the spec sheet** - which is exactly why
v3.5.0 made core placement a user setting instead of an assumption, and why the app measures it on
the user's own phone rather than shipping a number.

**3. The gain from thread tuning scales with core heterogeneity.** The naive arm spreads 8 threads
across every core, so the wider the spread in core strength, the worse the straggler penalty. The
nine-SoC set is still directionally consistent and still not a fit:

| SoC | clock spread (top / slowest) | thread step |
|---|---|---|
| MT6833 | 1.10x | 1.79x |
| MT6878 | 1.25x | ~1.8x |
| MT6765H | 1.28x | 1.34x |
| MT6886 | 1.40x | 3.80x |
| MT6897 | 1.52x | 1.65x |
| SM8250 | 1.57x | 4.25x |

Two rows deliberately break the trend, and both are informative. The **MT6765H** has an A78-like
clock spread on paper but every core is a Cortex-A53: when all eight cores are equally weak there
is no straggler to strand, so there is little to win - the mechanism is *relative* core strength,
which clock alone only proxies. The **MT6897** has the widest clocks of any MediaTek here and the
smallest gain, because its prime sits only 4.7% above its big cluster in practice.

Clock spread is therefore the wrong single predictor. `cpu_capacities` - which the prefill rule
already uses, and which the table has carried since Bench v2.0.0 (9 of 22 rows; the 13 app-1.5.0
rows are null) - is the better candidate. Testing it against this table is a measurement nobody has
made yet, and it needs the older devices resubmitted on a current build first.

See `plots/contributed_multidevice.png` for both steps plotted per device.

## What it falsified

`OPTIMIZATIONS.md` used to predict that on a flagship with more than four performance cores the
thread derivation would "count the larger top frequency cluster, yielding 5 or 6 generation
threads". The dataset showed the opposite, and the reason is structural: the rule counted cores
within 10% of the fastest **clock**, and every modern flagship puts its prime core 17-20% above
its own big cluster.

| device | top | 0.9 x top | cores passing | after clamp |
|---|---|---|---|---|
| Dimensity 7300 | 2500 MHz | 2250 | 4 | 4 |
| Tensor G5 | 3782 MHz | 3404 | **1** | 2 |
| SM8550 | 3360 MHz | 3024 | **1** | 2 |
| SM8450 | 2995 MHz | 2696 | **1** | 2 |
| SM8250 | 2841 MHz | 2557 | **1** | 2 |
| MT6886 | 2800 MHz | 2520 | 2 | 2 |

Prompt processing inherited that number (`n_pp = n_gen`), so prefill ran on two threads on every
flagship. The visible symptom: a Dimensity 7300 prefills Llama-3.2-1B-Q4_0 at **139 tok/s** while
an SM8550 - stronger silicon, with i8mm - manages **111**.

Both development phones were immune for a specific reason. The CMF is a flat 4+4, and the OPPO's
prime sits only 4.3% above its big cluster (2304 vs 2208 MHz) - both inside the 10% window. The
bug was invisible on every device the author owned. That is the argument for the dataset in one
sentence.

Fixed in v3.5.0 by decoupling the prefill width; see
[`OPTIMIZATIONS.md`](../docs/OPTIMIZATIONS.md) §1 and §2.

## The CPH2737 rows, and a second bug they exposed

Seven of the twenty-two rows are one OPPO Reno (CPH2737). Four things about them matter.

**It is not a Snapdragon.** It reports `mt6897` - a MediaTek Dimensity 8300. Worth stating because
the bug it exposed is an OEM kernel unit quirk, and attributing it to the wrong vendor would send
the next person looking in the wrong place.

**Its power figures are invalid, and not for the usual reason.** They were produced by a build
carrying a `PowerMath` bug: `EXTRA_VOLTAGE` is documented in millivolts and this device reports
whole **volts**, which made the microamp/milliamp heuristic fall through and under-report power by
1e6 - 2.7 microwatts of decode, 11 million tok/W. Fixed in chat v3.6.0 / Bench v2.1.0. Rows 11 and
12 wrongly carried `power_valid = true` and have been corrected to `false`. **Decode and prompt
throughput on those rows are unaffected and remain usable.**

**Each run was uploaded twice.** Ids 9=14, 10=13 and 11=12 are byte-identical pairs. `submission_id`
is minted fresh per upload, so its unique constraint prevents a *retried* insert creating a
duplicate but does nothing about the same stored result being uploaded again. Any aggregate over
the raw table double-counts this device. Not yet fixed.

What the device does tell us, from its one clean unplugged run: naive 18.1 -> threads-only 29.9 ->
optimized 30.5 tok/s. **1.65x from thread count, +2.0% from pinning** - consistent with everything
else here. v3.5.0's prefill fix changes nothing on this SoC, because its prime sits only 4.7% above
its big cluster, so `n_gen` and `n_pp` both resolve to 4 before and after.

One oddity flagged rather than claimed: its efficiency arm returns exactly 30.5 tok/s, identical to
optimized, when it should be pinned to the little cores and slower. Single pass, so it is a lead,
not a finding.

**The voltage fix did not fully fix this device.** Row 24 is the same phone on app 2.1.1, unplugged,
and it now carries `power_valid = true` - but it reports **0.52 W threads-only / 0.66 W optimized**
and 63.4 / 50.1 tok/W. A 1B model decoding at 33 tok/s does not run on half a watt. The unit
confusion is gone; something in this OEM's battery telemetry is still wrong, and the app's own
validity flag does not catch it. Its throughput is excellent and trustworthy - naive 18.3 ->
threads-only 32.7 -> optimized 33.3 tok/s, the fastest decode in the dataset - and its power is
excluded everywhere by the under-0.8 W rule below. Flagged, not fixed: `power_valid` needs a
plausibility floor, not just a unit heuristic.

## Reading rules

These are not style preferences. Ignoring them produces claims the data cannot carry.

- **Never quote power or tok/W from a charging run.** A charging phone reports the charger's
  current, not the workload's. The `power_valid` column exists for this; the view
  `bench_results_valid_power` filters on it.
- **Do not pool single-pass rows with 3-pass rows unweighted.** Rows with `runs_per_arm = 1`
  carry `sd = 0` because there is nothing to vary, not because they are precise. Two back-to-back
  single-pass runs on the same phone, model and quantization disagree by up to **19.6%**. That is
  the 1-pass noise floor.
- **Check relative standard deviation before using an arm.** The Galaxy S23 row's naive arm is
  6.72 +/- 5.95 tok/s - an **88.5% RSD**. It is noise, not a measurement, and any speedup ratio
  built on it is meaningless. The S22 Ultra gives a clean figure on comparable silicon.
- **Do not compare across models.** The Q8_0 rows are Qwen2.5-0.5B; the Q4_0 rows are
  Llama-3.2-1B. A 7.7 tok/W and a 3.4 tok/W from those two are not the same measurement.
- **Discard power that is physically implausible even when `power_valid` is true.** Two tests, both
  implemented in the site's leaderboard: arms of the same run disagreeing on watts by more than 4x,
  or a decode arm reading under 0.8 W. Row 24 fails the second. `power_valid` is a unit-heuristic
  flag, not a sanity check, and it has now been wrong in both directions on the same device.
- **Do not read `kleidiai_accelerated` as "Arm kernels ran".** It reports that the *quantization*
  is one KleidiAI has kernels for (Q4_0 / Q8_0), not that the ISA is present. The TECNO KI5q row is
  the proof: `cpu_flags` is empty - no dotprod, so no KleidiAI kernel can possibly execute - and
  the row still says `kleidiai_accelerated = true`. The column name overpromises; treat it as
  "quantization is eligible" and check `cpu_flags` for the rest.

## Known non-issues

Three things in the data look like bugs and are not:

- `duration_min = 0` on every row - that is the sustained-run field, hardcoded 0 for ablations.
- The naive arm reports `pinned: true` - it requests 8 threads, and the pinned set is built from
  the 8 fastest cores, i.e. all of them. The mask is every core, so it is a genuine naive
  baseline. Only the label is misleading.
- A row with three arms instead of four - the efficiency arm is a user toggle.

## Open questions the dataset cannot yet answer

- **Is 2 the right decode width on prime-core silicon?** On five SoCs now (Tensor G5, SM8550,
  SM8450, SM8250, MT6886) `n_gen` lands on `N_THREADS_MIN`, so the value is a floor rather than a
  derivation, and nobody has swept 2/4/6 there. The SM8250's 4.25x and the MT6886's 3.80x say the
  floor is at least not harmful; they do not say it is optimal. This remains the single
  highest-value missing measurement. SWEEP mode in ENTITY Bench exists precisely for it.
- **Does i8mm help prefill?** Still open, and the new rows sharpen the question rather than settle
  it. The A015 without i8mm prefills at 140 tok/s; the SM8550 with i8mm manages 111 and the MT6886
  with i8mm and SVE2 manages 63. Thread count is confounded throughout (4 vs 2), and now model
  fit and memory bandwidth are too. A controlled sweep on one device with i8mm, varying only
  thread count, is the measurement that would answer it.
- **Why did the MT6765H prefill get *slower* when tuned?** 9.3 tok/s naive -> 7.8 optimized, the
  only prompt regression in the dataset. All eight cores are Cortex-A53 and the "fast" cluster is
  the higher-clocked half; the prefill rule prefers capacity-ranked cores and may be selecting the
  wrong four here. Single pass, so it is a lead. It is also the only armv8.0-baseline device in the
  dataset, which makes it the one worth chasing.
- **Exynos: nothing at all.** Zero rows, still. See
  [`dt_thread_rules.py`](dt_thread_rules.py) for what the rules *predict* there without a device.

## Querying it

Schema and policy: [`contribute-schema.sql`](contribute-schema.sql). Two views ship with it:

```sql
-- Only rows that can legitimately carry a power or tok/W claim.
select * from public.bench_results_valid_power order by received_at;

-- One row per distinct SoC, newest first: the "does it generalise" question.
select * from public.bench_results_by_soc;
```

Per-arm figures live in the `arms` jsonb. To pull the pinning comparison specifically:

```sql
select device_model, soc, power_valid,
       max(case when a->>'arm' = 'threads_only' then (a->>'decode_tok_s')::numeric end) as unpinned,
       max(case when a->>'arm' = 'optimized'    then (a->>'decode_tok_s')::numeric end) as pinned
  from public.bench_results r, lateral jsonb_array_elements(r.arms) a
 group by 1,2,3;
```

Accepted rows belong in [`results/`](results/) beside the hand-collected CSVs - the dataset is
only evidence if it is auditable. The Q4_0 rows are exported as
[`results/contributed_ablation_q4_0_20260723.csv`](results/contributed_ablation_q4_0_20260723.csv),
which is what [`plot_contributed.py`](plot_contributed.py) reads, so every number in
`plots/contributed_multidevice.png` traces to a file in the repo rather than to a live query.

That export stops at 2026-07-23 and therefore predates the four SoCs added above. The figures in
this file were read from the table directly; re-exporting the CSV and regenerating the plot is
outstanding work.

## Reading it without SQL

The project site renders this table with every rule above enforced in the markup:
**<https://kkjjkamal123.github.io/ENTITY-WEB/leaderboard/>**. It fetches
`public.bench_results` from the browser, falls back to a committed snapshot when offline, and says
which of the two you are looking at. Power columns stay empty where `power_valid` is false, rows
failing the plausibility tests are marked `excl.`, single-pass rows are marked provisional, and
high-variance arms are flagged and kept out of the aggregates - the same judgments this file
argues for, applied automatically rather than by the reader. Source and the page-to-file map are in
[kkjjkamal123/ENTITY-WEB](https://github.com/kkjjkamal123/ENTITY-WEB).
