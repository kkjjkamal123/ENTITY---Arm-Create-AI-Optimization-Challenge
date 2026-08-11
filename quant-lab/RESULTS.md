# Quantization quality vs KleidiAI eligibility — Llama-3.2-1B-Instruct

Measured 2026-08-06. Host: i5-12450HX, llama.cpp `e700bfb`, gcc 16.1.0 (w64devkit 2.9.0).

## Method

- **One source.** Every quant below is produced from a single F16 GGUF
  (`bartowski/Llama-3.2-1B-Instruct-GGUF`, 2,479,595,360 bytes). Any difference is the
  quantizer's, not the publisher's.
- **Disjoint calibration and evaluation.** The importance matrix is computed over
  `calibration_datav3.txt` (100 chunks); perplexity is measured over wikitext-2 raw
  **test** (200 chunks). Calibrating on the eval set would inflate the imatrix result.
- **Fixed run parameters.** Every perplexity run uses `--chunks 200 -t 8`. ggml's
  reduction order depends on thread count, so a varying `-t` would make runs
  incomparable.
- **Perplexity is host-computed.** It is a deterministic function of weights and eval
  text and does not depend on the device. Speed numbers, which do, are measured on the
  phone separately.
- **Eligibility is read from the GGUF tensor table**, not inferred from the filename.
  KleidiAI registers matmul kernels for `Q4_0` and `Q8_0` only; every other tensor type
  falls back to generic ggml regardless of the file's declared `file_type`.

## Results

| Quant | PPL (200 chunks) | vs F16 | Bytes | Weights missing KleidiAI |
|---|---:|---:|---:|---:|
| F16 (reference) | 14.2580 ± 0.17724 | — | 2,479,595,360 | n/a |
| **Q8_0** | 14.2705 ± 0.17741 | +0.09% | 1,321,082,720 | **0.0%** |
| Q4_K_M | 14.7346 ± 0.18241 | +3.34% | 807,694,176 | 100.0% |
| **Q4_0-kopt** (this work) | 15.6084 ± 0.19374 | +9.47% | 836,640,832 | **2.7%** |
| Q4_0 imatrix (catalog) | 15.6159 ± 0.19391 | +9.52% | 773,025,856 | 24.0% |
| Q3_K_L | 16.0927 ± 0.20031 | +12.87% | 732,524,384 | 100.0% |
| Q4_0 naive | 16.5272 ± 0.20912 | +15.92% | 770,928,480 | 21.3% |

## Finding 1 — the imatrix gap is real and undisclosed

Naive `llama-quantize … Q4_0` produces a file **2,097,376 bytes smaller** and **5.51%
worse in perplexity** (16.5272 vs 15.6159) than the imatrix-calibrated Q4_0 that
bartowski publishes and ENTITY's catalog ships. The two files carry the same name, the
same declared `file_type`, and nothing in the tooling distinguishes them.

The imatrix recovers 0.9113 PPL of the 2.2692 lost to quantization — **40% of the total
quantization damage** — for 0.27% more bytes.

## Finding 2 — a file named Q4_0 is not all Q4_0

| File | Non-eligible tensors |
|---|---|
| Q4_0 naive | `token_embd.weight` @ Q6_K — 262.7M params |
| Q4_0 imatrix (catalog) | `token_embd.weight` @ Q6_K + `blk.0/1.ffn_down.weight` @ Q4_1 — 296.2M params |

Llama-3.2-1B uses **tied embeddings** — there is no separate `output.weight` — so
`token_embd.weight` is also the output projection: a 2048×128256 GEMM evaluated at every
decode step and every prefill position. **The largest matmul in the model is off the
KleidiAI path, in the quantization chosen because it reaches KleidiAI.**

The two `Q4_1` tensors come from `src/llama-quant.cpp:619-622`, which promotes the first
`n_layer/8` `ffn_down` layers when an imatrix is supplied. So the importance matrix
*improves quality and simultaneously reduces KleidiAI coverage*, 21.3% → 24.0%. Neither
effect is visible to a user.

## Finding 3 — eligibility can be bought for size, not quality

`Q4_0-kopt` is Q4_0 with `--token-embedding-type q8_0` and the same imatrix:

| | catalog Q4_0 | Q4_0-kopt | change |
|---|---:|---:|---:|
| Perplexity | 15.6159 | 15.6084 | −0.05% (within noise) |
| Bytes | 773,025,856 | 836,640,832 | +8.23% |
| Weights missing KleidiAI | 24.0% | 2.7% | **−21.3 points** |

21.3 percentage points of KleidiAI coverage for 8.2% file size, at no measurable quality
cost. Whether that converts into throughput is a device question — see the phone
measurements.

Residual 2.7% is the two `Q4_1` `ffn_down` tensors, which `--tensor-type` could also
pin to Q4_0 at some quality cost. Not attempted here.

## Implication for ENTITY

`ModelCatalog.kt:27` currently reads:

```kotlin
val kleidiAccelerated: Boolean get() = quant == "Q4_0" || quant == "Q8_0"
```

This keys off the catalog label. On the file the catalog actually ships, 24.0% of weights
cannot reach KleidiAI, including the model's largest matmul. The advisor should read the
GGUF tensor table — which `GgufMetadataReader` already parses — and report the measured
eligible fraction rather than a boolean derived from a name.

That is the same per-tensor discipline as the upstream fix this project already landed in
[llama.cpp #25701](https://github.com/ggml-org/llama.cpp/pull/25701).

## Recommendation table

| Situation | Quant | Rationale |
|---|---|---|
| RAM available | **Q8_0** | 0.0% ineligible, +0.09% quality cost |
| Tight RAM, want Arm kernels | **Q4_0-kopt** | 2.7% ineligible, +8.2% size, no quality cost |
| Tight RAM, quality first | **Q4_K_M** | +3.34% quality cost, but 100% ineligible |
| — | ~~Q4_0 naive~~ | dominated by Q3_K_L on both size and quality |

## On-device speed — the prediction was wrong

CMF Phone 1, Dimensity 7300, 4 threads pinned to the A78 cluster (`taskset f0`), 3 reps,
90 s cooldown between models, freshly rebooted, 36 °C at start.

| | Q4_0 (76% eligible) | Q4_0-kopt (97% eligible) | change |
|---|---:|---:|---:|
| pp512 | 125.69 ± 1.43 | 121.84 ± 3.00 | **-3.1%** |
| tg128 | 17.99 ± 0.15 | 15.94 ± 0.77 | **-11.4%** |

Predicted before measuring: prefill faster, decode slower. Decode was right; prefill was
backwards — it got slower too.

**Why.** llama.cpp computes logits only for the final position of a prompt, so the output
projection runs once across a 512-token prefill rather than 512 times. Promoting it to Q8_0
buys almost nothing there and the extra bytes cost a little. In decode it runs every token,
but decode is bandwidth-bound, so enlarging the single largest tensor is a straight loss.

**Coverage is not throughput.** The 24% ineligible figure is true and is not costing this
model speed. `1b-q4_0-kopt.gguf` is a negative result, not a recommendation.

The harness validates against the project's own published record: tg128 17.99 here against
18.1 tok/s published for the same device, model and workload.

## Output equivalence — the speedups do not change the answer

Every speed number this project publishes comes from changing how work is *scheduled*: how
many threads decode uses and which cores they run on. None of it touches the weights, the
sampler or the arithmetic, so the natural assumption is that the answer is unchanged and
only the wait is shorter.

That assumption is not free, and this repository already depends on it twice. ggml splits a
row's dot product across threads and sums the partial results, so the thread count fixes the
order of a floating-point reduction. Floating-point addition is not associative: different
order, different last bits; different last bits in a logit, and a token that was a near-tie
can flip. `ppl-sweep.sh` holds the thread count constant across runs for exactly this
reason. It had never been measured.

CMF Phone 1, Dimensity 7300 (MT6878), 4× A78 + 4× A55. `1b-q4_0-stock.gguf` — the same
quant-lab Q4_0 the tables above are built from. Two instruments per arm: 96 greedy tokens
(`--temp 0 --seed 42`, one fixed prompt) and perplexity over 12 chunks of wikitext-2 test at
`n_ctx=512`, printing a running per-chunk value. Generation is what a user sees but is
insensitive — a logit must move far enough to change an argmax before it shows at all.
The per-chunk series is the sensitive instrument: each number is a function of hundreds of
logits, so one perturbed accumulation moves a digit.

| arm | threads | affinity | effective CPUs | generation vs `auto` | per-chunk PPL vs `auto` | final PPL |
|---|---:|---|---|---|---|---:|
| `naive` | 8 | all cores | 0-7 | byte-identical | identical, 12/12 | 19.2128 |
| `threads` | 4 | scheduler | 0-7 | byte-identical | identical, 12/12 | 19.2128 |
| `auto` | 4 | `taskset f0` | 4-7 | (reference — what ENTITY ships) | (reference) | 19.2128 |
| `efficiency` | 4 | `taskset 0f` | 0-3 | byte-identical | identical, 12/12 | 19.2128 |

**The speedup is free of any change in output.** `naive` against `auto` is the pair that
matters: it is the only comparison in the set where the reduction width actually changes,
8 threads against 4, and it is also the baseline the decode gain is measured from. Both
arms produced the same 96 tokens byte for byte and the same twelve per-chunk values.

### Three controls, because "identical" is what a broken test also prints

A comparison that can only ever say "identical" proves nothing — empty files and a file
compared with itself both look like the good result.

| control | expected | observed |
|---|---|---|
| repeat — the reference arm run a second time, unchanged | identical | identical ✓ |
| perturbed — the reference arm with one word added to the prompt | differs | differs ✓ |
| batch — the reference arm over 3 chunks instead of 12 | differs | differs ✓ |

The third control is the one that makes the perplexity column mean something, and it was
found rather than designed. An earlier 3-chunk smoke run scored chunk 1 at **8.3443** where
the 12-chunk run scores **8.3423** — same 512 tokens of wikitext, same model file, same
binary, same 4 threads on the same cluster. `llama-perplexity` packs chunks into one batch,
so the chunk total sets how many sequences are scored together and therefore the shape of
the matmuls scoring them; three sequences in a batch accumulate differently from four.

| | chunk 1 | chunk 2 | chunk 3 |
|---|---:|---:|---:|
| 12-chunk run | 8.3423 | 11.0529 | 13.1472 |
| 3-chunk control | 8.3443 | 11.0544 | 13.1479 |

So the instrument does move when the arithmetic is reorganized — about 2 parts in 10,000.
Doubling the thread count and moving the work between an A78 cluster and an A55 cluster
moved it by zero.

### What this does and does not license

- It licenses: *on this device and this model, thread count and core placement changed the
  wait and nothing else.* That is the claim the site can now make beside its speed numbers.
- It does not license "bit-exact". The per-chunk series is printed to four decimals, so
  "identical" means agreement to that resolution — the batch control establishes only that
  a ~2e-4 relative perturbation is visible here, not that a smaller one would be.
- Greedy decoding only. At temperature > 0 the sampler's RNG dominates and the question
  changes.
- One device, one model, one quantization, 12 chunks. `naive` is the arm with a genuinely
  different reduction width; the other two share `auto`'s four threads and differ only in
  where those threads ran.

### Verify it without a device

The raw outputs are committed, so checking this claim needs nothing but the checkout.

```bash
cd quant-lab/results/equivalence

# 8 threads vs the shipped 4 - the only pair where the reduction width actually
# changes, and the same pair the published decode gain is measured across.
cmp auto-gen.txt naive-gen.txt && echo "96 greedy tokens: byte-identical"

# The sensitive instrument: one perplexity value per 512 tokens of wikitext.
diff <(grep -oE '\[[0-9]+\][0-9.]+' auto-ppl.txt) \
     <(grep -oE '\[[0-9]+\][0-9.]+' naive-ppl.txt) && echo "12/12 chunks: identical"
```

Both pass, as do `threads-*` and `efficiency-*`. Then check that the test is capable of
failing, because an empty file compared with itself looks exactly like the good result:

```bash
cmp auto-gen.txt control-repeat-gen.txt      # same config twice -> identical
cmp auto-gen.txt control-perturbed-gen.txt   # one word added    -> differs

grep -oE '\[[0-9]+\][0-9.]+' auto-ppl.txt | head -3          # 8.3423  11.0529  13.1472
grep -oE '\[[0-9]+\][0-9.]+' control-batch-ppl.txt | head -3 # 8.3443  11.0544  13.1479
```

That last pair is the whole argument: the arithmetic can be reorganised into different
numbers, and changing the thread count did not do it.

Re-measure on your own device with `./stage-equivalence.sh all` (runner: `equivalence.sh`).
`ARMS=` finishes an interrupted run one arm at a time.

## Pending

- The i8mm path is unmeasured — needs the OPPO CPH2729. This phone is `asimddp` only, so it
  cannot test where KleidiAI's documented Q8_0 win lives.
