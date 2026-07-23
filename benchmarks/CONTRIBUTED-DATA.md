# The contributed dataset - what it is, and what it has already changed

[`CONTRIBUTE-BACKEND.md`](CONTRIBUTE-BACKEND.md) explains how results reach the database.
This file is about the results themselves: what has arrived, what it establishes, what it
falsified, and the rules for reading it without drawing a claim it cannot support.

The dataset exists because this project's central finding is a claim about **Arm silicon**, and
two phones cannot make that claim. Everything below came from devices the author does not own.

## What is in it

As of 2026-07-23: **12 rows, 5 SoCs**, all app 1.5.0.

| device | SoC | ISA flags | topology (MHz) |
|---|---|---|---|
| Nothing A015 (x3) | MediaTek MT6878 / Dimensity 7300 | dotprod, fp16 | 4x2000 + 4x2500 |
| Pixel 10 | Google Tensor G5 | dotprod, i8mm, sve, sve2, fp16 | 2x2246 + 5x3052 + 1x3782 |
| Galaxy S23 | Qualcomm SM8550 | dotprod, i8mm, fp16 | 3x2016 + 4x2803 + 1x3360 |
| Galaxy S22 Ultra | Qualcomm SM8450 | dotprod, i8mm, fp16 | 4x1785 + 3x2496 + 1x2995 |
| OPPO CPH2737 (x6) | MediaTek MT6897 / Dimensity 8300 | dotprod, i8mm, fp16 | 4x2200 + 3x3200 + 1x3350 |

This is the project's first i8mm silicon and its first SVE2 device. Four of the five SoCs are
prime-core designs, which neither development phone effectively is - and that difference is what
made the dataset immediately useful.

## What it establishes

**1. Thread-count tuning is the lever that generalises.** Going from the naive 8-thread
configuration to the derived thread count pays on every SoC measured: 1.65x to 3.58x. No device
regressed.

**2. Pinning is a speed lever with a power cost - not an energy lever.** This is the finding
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

Median: **+0.6% decode, -1.5% tok/W**, positive on only 3 of 6 rows. The Pixel 10 is the clean
demonstration - pinning is 29.3% faster and draws 33.5% more power, so tokens per watt goes
*down*. This is why v3.5.0 made core placement a user setting instead of an assumption, and why
the app now reports the measurement from the user's own phone.

**3. The gain from thread tuning scales with core heterogeneity.** A flat 4+4 device gets ~1.8x;
the widest-spread flagships get ~3.5x. The naive arm spreads 8 threads across every core, so the
wider the spread in core strength, the worse the straggler penalty. Directionally consistent, not
yet a fit - and the CPH2737 at 1.65x is the reminder that a narrow prime gap behaves like a flat
device, not like a flagship.

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

Six of the twelve rows are one OPPO Reno (CPH2737). Three things about them matter.

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

## Known non-issues

Three things in the data look like bugs and are not:

- `duration_min = 0` on every row - that is the sustained-run field, hardcoded 0 for ablations.
- The naive arm reports `pinned: true` - it requests 8 threads, and the pinned set is built from
  the 8 fastest cores, i.e. all of them. The mask is every core, so it is a genuine naive
  baseline. Only the label is misleading.
- A row with three arms instead of four - the efficiency arm is a user toggle.

## Open questions the dataset cannot yet answer

- **Is 2 the right decode width on prime-core silicon?** On the three flagships `n_gen` lands on
  `N_THREADS_MIN`, so the value is a floor rather than a derivation, and nobody has swept 2/4/6
  there. This is the single highest-value missing measurement. SWEEP mode in ENTITY Bench exists
  precisely for it.
- **Does i8mm help prefill?** Currently the data says no - the A015 without i8mm prefills at 139
  tok/s against the S23's 111 - but thread count is confounded (4 vs 2), so the question is open
  rather than answered. Re-measuring after v3.5.0, which fixes that confound, is the next step.
- **Exynos: nothing at all.** Zero rows. See
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
