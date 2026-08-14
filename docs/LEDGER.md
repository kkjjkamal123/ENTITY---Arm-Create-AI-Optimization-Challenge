# Experiment ledger

Every optimization this project tried, and what happened to it. One row per experiment, with
the outcome, the number that decided it, and where the raw evidence lives.

The rule for this file: **an experiment that was reverted or produced nothing gets a row of
exactly the same weight as one that shipped.** The reverted ones are the reason the surviving
numbers are worth reading — a project that only records its wins has no way to show it was
ever at risk of being wrong.

Outcomes:

| | |
|---|---|
| **KEEP** | shipped, and the number that justifies it held up |
| **REVERT** | tried, measured, removed or demoted — the measurement said no |
| **NO EFFECT** | ran correctly, changed nothing worth claiming |
| **OPEN** | modelled or built, not yet settled on silicon |

Evidence grade: **A** = repeated runs with stated spread on a controlled protocol · **B** =
single controlled run or a contributed row · **C** = indicative, single pass, noise floor not
established.

---

## KEEP

| Experiment | Outcome | Evidence | Grade |
|---|---|---|---|
| Derive decode thread count from the top frequency cluster instead of hardcoding | **KEEP** | +39% decode on Dimensity 7300, +80% on Snapdragon 6 Gen 4, five runs per arm | A |
| Ship seven CPU backend variants and score them against the chip at load | **KEEP** | One device, seven backends forced in turn: dotprod is **+379.4% prompt / +1.1% decode**. `quant-lab/results/isa-dispatch/` | A |
| Derive the *prefill* thread width separately, from kernel `cpu_capacity` | **KEEP** | Fixes the flagship collapse below; Dimensity 7300 prefills 139 tok/s against an SM8550's 111 under the old rule | B |
| Core pinning, shipped as a **setting** rather than a default | **KEEP** | Device dependent: +21% decode on Dimensity 7300 (non-overlapping distributions), +1% decode but −30% power on Snapdragon 6 Gen 4 | A |
| Quantization gate: prefer Q4_0 / Q8_0 because KleidiAI has kernels for nothing else | **KEEP** | Q3_K_L prefills 43.4 tok/s against Q4_0's 128.2 on the same phone and model | A |
| Report **measured** KleidiAI coverage from the tensor table, not `general.file_type` | **KEEP** | A file named Q4_0 is 76% Q4_0; the census reconstructs file size to 1% | A |
| Adaptive context admission from free memory rather than installed RAM | **KEEP** | Lets a 3B-class model load on a phone reporting under 2 GB free instead of failing | B |
| Per-token thermal back-off (0 / 6 / 12 ms every eighth token) | **KEEP** | Sustained runs hold rate instead of collapsing; `benchmarks/results/` | B |
| KV session save/restore instead of re-decoding history each turn | **KEEP** | See `quant-lab/results/kv-restore/` — re-prime cost grows with history, restore does not | A |
| Size against a conservative assumed 1.5 GB when the memory query fails (3.7.1) | **KEEP** | Recommendation moves from a 4.4 GB download to 1.07 GB on a device reporting no free memory | B |
| Exclude a TIGHT fit from the recommender rather than penalising it (3.7.1) | **KEEP** | The −1.5 penalty was outbid by the capability term; it recommended 2.18 GB at 6.3 tok/s over 1.7B at 14.6 | B |

## REVERT

| Experiment | Outcome | Evidence | Grade |
|---|---|---|---|
| "+121% decode comes from pinning threads to the big cores" | **REVERT** | A three-arm ablation put the multiplier on the **thread count**, not the pin. Published as a retraction | A |
| …and then: "pinning earns roughly nothing" | **REVERT** | Overcorrected. Four arms, two vendors, five runs: pinning earns +21% decode on one SoC and −30% power on another | A |
| Promote the tied `token_embd` tensor Q6_K → Q8_0 to raise KleidiAI coverage 76% → 97% | **REVERT** | **Slower on both phases**: prefill −3.1%, decode −11.4%. The output projection runs once per prefill, so its share of prefill work is 0.041%, while the extra 63.6 MB costs every decode token. Rebuilt file published as a negative result, kept out of the catalog | A |
| Pin decode to the LITTLE cluster because efficiency cores "save battery" | **REVERT** | Loses on speed *and* tok/W on both phones; collapses prompt 139 → 82.5 tok/s, because prefill is the compute-bound GEMM an A55 is worst at | A |
| Thread rule counting cores within 10% of the fastest **clock** | **REVERT** | Every modern flagship puts its prime core 17–20% above its own big cluster, so the count collapsed to 1 and was clamped to 2. Prefill had been running on two threads on every flagship; both of my own phones were structurally immune | A |
| `power_valid` as a name for what was really a unit heuristic | **REVERT** | A device reporting whole volts where Android documents millivolts produced 2.7 µW and 11M tok/W. Two compounding 1000× errors. Renamed and re-gated on physical plausibility | A |
| Asserting a pinning energy win | **REVERT** | Isolated properly the median was +0.6% decode, −1.5% tok/W, positive on 3 of 6 rows. Demoted from a claim to a setting | A |

## NO EFFECT

| Experiment | Outcome | Evidence | Grade |
|---|---|---|---|
| fp16 vector arithmetic backend variant on top of dotprod | **NO EFFECT** | +0.1% prompt, +0.8% decode. The ISA ladder is one step, not a ramp | A |
| Does the speedup change the model's output? | **NO EFFECT — and that is the finding** | Four scheduling arms produce the same 96 greedy tokens byte for byte and the same twelve per-chunk perplexity values. Three controls, including one that shows the instrument *does* move at ~2e-4 when the batch shape changes | A |
| ADPF deadline hints | **NO EFFECT (claimed)** | Shipped as a measurable arm; has not run on enough devices to say anything. No speedup is claimed for it | C |

## OPEN

| Question | Status | Why it is not settled |
|---|---|---|
| What the i8mm rung is worth | **OPEN** | Modelled by the catalog and the probe; the one-device ladder shows the rung exists but a Cortex-A78 cannot load it. Needs silicon with i8mm under controlled conditions |
| Why the Helio G37 prefills *slower* tuned than naive (9.3 → 7.8 tok/s) | **OPEN** | All eight cores are A53s, so capacity-ranked selection is probably picking the wrong four. Single contributed pass, so a lead rather than a result |
| GPU and NNAPI as alternatives to the tuned CPU path | **OPEN** | The shipped build is CPU-only. Nothing here yet measures what a Vulkan or NNAPI path would do on this class of hardware, so "CPU is the right choice" is currently a design decision and not a measurement |
| Decode thread width on prime-core flagships | **OPEN** | The count lands on a floor clamp rather than a derivation. Bench's sweep mode exists to answer it; no flagship has run it |
| A plausibility floor for `power_valid` inside the app | **OPEN** | Currently enforced in analysis and on the leaderboard, not at the point of measurement |

---

## Why this file exists

Both halves of a comparison need the same standard of evidence, and the natural failure mode
of an optimization project is to measure carefully when a change works and stop measuring
when it does not. Four of the seven REVERT rows above were things this project had already
published as wins. One of them — "pinning earns roughly nothing" — was a *retraction* that
itself needed retracting, which was harder to notice than the original error, because being
wrong in the cautious direction feels like rigour.

Raw data for every row: `benchmarks/results/`, `quant-lab/results/`, and the contributed
dataset at <https://kkjjkamal123.github.io/ENTITY-WEB/leaderboard/>.
