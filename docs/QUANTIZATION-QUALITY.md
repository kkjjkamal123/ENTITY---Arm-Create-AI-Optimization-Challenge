# What quantization costs, and what KleidiAI coverage is actually worth

[`KLEIDIAI-QUANTS.md`](KLEIDIAI-QUANTS.md) established that Arm's KleidiAI has kernels for
`Q4_0` and `Q8_0` only. This page answers the two questions that finding left open.

The first is the one this repository had asserted four times without a number: **Q4_0 is a
quality tradeoff.** How much of one?

The second came out of looking at the files properly, and it produced a result I did not
expect and had predicted backwards.

---

## The prediction that was wrong

A file named `Q4_0` is not Q4_0 throughout. Reading the tensor table of the exact file this
project's catalog ships - bartowski's `Llama-3.2-1B-Instruct-Q4_0.gguf` - gives:

| Tensor | Type | Parameters |
|---|---|---:|
| 110 block weights | Q4_0 | 939.5M |
| `token_embd.weight` | **Q6_K** | 262.7M |
| `blk.0.ffn_down.weight` | **Q4_1** | 16.8M |
| `blk.1.ffn_down.weight` | **Q4_1** | 16.8M |

**24.0% of the quantized weights cannot reach KleidiAI.** Llama 3.2 ties its embeddings -
there is no separate `output.weight` - so that Q6_K tensor is also the output projection, a
2048x128256 GEMM. The largest matmul in the model sits off the fast path, in a quantization
chosen because it reaches the fast path.

That looked like a bug worth fixing, so I fixed it. `llama-quantize` accepts
`--token-embedding-type`, and Q8_0 is the *other* type KleidiAI serves, so the embedding can
be promoted without leaving the eligible set:

```bash
llama-quantize --imatrix llama-1b.imatrix --token-embedding-type q8_0 \
    Llama-3.2-1B-Instruct-f16.gguf 1b-q4_0-kopt.gguf Q4_0
```

That takes coverage from 76% to 97.3% for 8.2% more file, and costs nothing in quality
(perplexity 15.6084 against 15.6159 - a difference of 2.5% of one error bar).

**The prediction, written down before measuring:** prefill gets faster, because it is a
compute-bound GEMM and that is what KleidiAI accelerates; decode gets slower, because it is
bandwidth-bound and the tensor got about 30% heavier.

**The measurement**, CMF Phone 1, Dimensity 7300, four threads pinned to the A78 cluster,
three repetitions, 90-second cooldown between models:

| | Q4_0 (76% eligible) | Q4_0-kopt (97% eligible) | change |
|---|---:|---:|---:|
| Prefill, pp512 | 125.69 ± 1.43 tok/s | 121.84 ± 3.00 tok/s | **-3.1%** |
| Decode, tg128 | 17.99 ± 0.15 tok/s | 15.94 ± 0.77 tok/s | **-11.4%** |

Half of that prediction was right and half was backwards. Decode got slower, as expected.
Prefill did not get faster - it got slightly slower too.

**Why.** llama.cpp computes logits only for the final position of a prompt, so the output
projection runs *once* across a 512-token prefill, not 512 times. Promoting it buys almost
nothing there, while the extra bytes cost a little. In decode it does run every token, but
decode is bandwidth-bound, so making the single largest tensor 30% heavier is a straight
loss.

**The conclusion is the inverse of the one I set out to demonstrate.** Raising KleidiAI
coverage from 76% to 97% made the model slower on both axes. Coverage is not throughput. The
24% figure is true, and it is not costing this model any speed, because the ineligible tensor
is the one the workload barely touches in prefill and can only be hurt by enlarging in decode.

`1b-q4_0-kopt.gguf` is therefore **not** recommended, and is not in the catalog. It is
published as a measured negative result.

What survives is narrower and still worth having: the app should report what it measured
rather than what a filename implies. The model card now reads the tensor table and says
"KleidiAI partial (76% of weights)" where it used to say "KleidiAI active". That is the same
per-tensor discipline as [llama.cpp PR #25701](https://github.com/ggml-org/llama.cpp/pull/25701),
which this project landed upstream, applied one level up.

---

## What quantization costs in quality

Method, because the numbers are worthless without it:

- **One source.** Every quant below is produced from a single F16 file
  (`bartowski/Llama-3.2-1B-Instruct-GGUF`, 2,479,595,360 bytes). A difference between two rows
  is the quantizer's, not a difference between publishers.
- **Disjoint calibration and evaluation.** The importance matrix is computed over
  `calibration_datav3.txt`; perplexity is measured over wikitext-2 raw **test**. Calibrating on
  the evaluation set is the standard way this measurement gets inflated.
- **Fixed parameters.** Every run is `--chunks 200 -t 8`. ggml's reduction order depends on
  thread count, so a varying `-t` makes runs incomparable at this precision.
- **Perplexity is host-computed.** It is a deterministic function of weights and evaluation
  text; it does not depend on the device. The speed numbers above, which do, were measured on
  the phone.
- **Coverage is read from each file's tensor table**, not from `general.file_type`.

| Quant | Perplexity | vs F16 | Bytes | KleidiAI coverage |
|---|---:|---:|---:|---:|
| F16 (reference) | 14.2580 ± 0.17724 | — | 2,479,595,360 | n/a |
| **Q8_0** | 14.2705 ± 0.17741 | +0.09% | 1,321,082,720 | **100%** |
| Q4_K_M | 14.7346 ± 0.18241 | +3.34% | 807,694,176 | 0% |
| Q4_0 imatrix *(catalog)* | 15.6159 ± 0.19391 | +9.52% | 773,025,856 | 76% |
| Q3_K_L | 16.0927 ± 0.20031 | +12.87% | 732,524,384 | 0% |
| Q4_0 no imatrix | 16.5272 ± 0.20912 | +15.92% | 770,928,480 | 79% |

### The number this project owed

**Q4_0 costs 5.6% perplexity against Q4_K_M** (15.6159 against 14.7346) for 4.5% fewer bytes.
That is the price of the prompt-processing win in
[`KLEIDIAI-QUANTS.md`](KLEIDIAI-QUANTS.md), and it is now a figure rather than a hedge. The
recommendation does not change - a 3.4x cut in time-to-first-token is worth 5.6% perplexity to
most people on a phone - but the user can now see both halves and decide.

### Naive Q4_0 is worse than you would guess

`llama-quantize model-f16.gguf out.gguf Q4_0`, the documented command, produces a file
**2,097,376 bytes smaller and 5.51% worse in perplexity** than the Q4_0 the catalog ships,
because the published one is imatrix-calibrated and a plain invocation is not. Both carry the
same filename and the same `general.file_type`. Nothing warns you.

The importance matrix recovers 0.9113 of the 2.2692 perplexity lost to quantization - about
40% of the total damage - for 0.27% more bytes.

It also *reduces* KleidiAI coverage, 79% to 76%: `src/llama-quant.cpp` promotes the first
`n_layer/8` `ffn_down` layers to Q4_1 when an imatrix is supplied. Better quality, less
coverage, from one flag, with neither effect visible from outside the file.

### Q3_K_L dominates naive Q4_0 outright

Q3_K_L is 38 MB smaller *and* 0.43 perplexity better. Anyone quantizing their own Q4_0 without
an imatrix would have been better served by a smaller K-quant on both axes - losing only the
KleidiAI path, which the measurement above suggests is worth less than it sounds.

---

## What to actually use

| Situation | Quant | Why |
|---|---|---|
| Memory to spare | **Q8_0** | +0.09% quality, every weight on a KleidiAI kernel |
| Tight memory, prompt latency matters | **Q4_0** *(imatrix - check the source)* | reaches KleidiAI, +9.5% quality cost, 3.4x faster TTFT than a K-quant |
| Tight memory, quality first | **Q4_K_M** | +3.3%, no Arm fast path at all |
| — | ~~Q4_0 without an imatrix~~ | beaten by Q3_K_L on size and quality together |

Note what the catalog does **not** do with this. It does not bias its recommendation toward
Q8_0 on quality grounds, because decode is bandwidth-bound and the same model in Q8_0 is 71%
larger. The measurement above is the evidence: an 8% file increase cost 11.4% of decode. The
quality figure is surfaced on the model card so the user can weigh it against a token rate
they can see, rather than spent on their behalf.

---

## Reproducing this

Host build of llama.cpp (`e700bfb`), then:

```bash
llama-imatrix    -m f16.gguf -f calibration_datav3.txt -o m.imatrix --chunks 100 -t 8
llama-quantize   --imatrix m.imatrix f16.gguf out-q4_0.gguf Q4_0 8
llama-perplexity -m out-q4_0.gguf -f wiki.test.raw --chunks 200 -t 8
```

Device numbers, with the phone idle, unplugged where power matters, and a cooldown between
models:

```bash
taskset f0 ./llama-bench -m model.gguf -p 512 -n 128 -t 4 -r 3
```

`taskset f0` pins to cores 4-7, the A78 cluster on the reference phone. Both models must get
the same mask or the comparison measures placement instead of the model.
