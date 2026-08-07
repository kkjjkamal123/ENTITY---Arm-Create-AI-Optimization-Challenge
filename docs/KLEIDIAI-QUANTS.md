# Which GGUF quant actually reaches KleidiAI

[Home](../README.md) · [Benchmarks](../benchmarks/BENCHMARKS.md) · [Optimizations](OPTIMIZATIONS.md) · [FAQ](FAQ.md) · [License](../LICENSE)

A standalone guide for any llama.cpp-based Android app. You do not need ENTITY to apply it. If you
ship a GGUF model on an Arm phone and expect Arm's KleidiAI kernels to accelerate it, this is the
one thing to check first.

## The trap in two sentences

KleidiAI registers matmul kernels for exactly two quantized GGML types, `Q4_0` and `Q8_0`. Load a
model in any other quantization and llama.cpp silently falls back to its generic CPU kernels, with
no log line to tell you it happened.

## Which types reach KleidiAI

Verified in `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` (`extra_buffer_type::supports_op`), which
gates every kernel on the tensor type.

| GGML type | Reaches KleidiAI matmul kernels |
|---|---|
| `Q4_0` | yes |
| `Q8_0` | yes |
| `F32` | yes (upstream, a non-quant type) |
| All K-quants (`Q3_K_*`, `Q4_K_*`, `Q5_K_*`, `Q6_K`, ...) | no, generic ggml fallback |
| All IQ types (`IQ2_*`, `IQ3_*`, `IQ4_*`, ...) | no, generic ggml fallback |
| Everything else | no, generic ggml fallback |

The fallback is independent of everything you might expect to control it. It happens regardless of
which CPU backend variant loaded at startup, and regardless of building with `GGML_CPU_KLEIDIAI=ON`.
Shipping an armv8.2+dotprod+KleidiAI backend does nothing for a model the library has no kernel for.

The fallback is silent: no warning, nothing in logcat. (An upstream patch adding a one-time warning
was contributed from this project in [llama.cpp PR #25701](https://github.com/ggml-org/llama.cpp/pull/25701),
merged upstream 2026-07-21 as commit `fb0e6b6`.) So a
benchmark on a K-quant can run for months while every person involved believes KleidiAI is doing
the work, and it never executed once.

## The measured cost

CMF Phone 1, MediaTek Dimensity 7300, Llama-3.2-1B, PP512, same four-thread unpinned config. The
only variable is the quantization.

| | Q3_K_L (733 MB, no Arm fast path) | Q4_0 (773 MB, Arm fast path) | Change |
|---|---:|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** | **+183%** |
| Derived TTFT (512-token prompt) | 12,050 ms | **4,299 ms** | **-64%** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s | -13% |

## Why prompt improves and decode does not

The two phases are bound by different resources, and the split is exactly what the hardware
predicts, which is why it is believable rather than a fluke.

- **Prompt processing is a compute-bound GEMM.** It is precisely what KleidiAI's dotprod and i8mm
  kernels exist to accelerate, and it nearly triples (about 3x).
- **Decode is memory-bandwidth-bound.** It tracks bytes-per-weight, not kernel quality. Q4_0 is
  773 MB against Q3_K_L's 733 MB, about 6% more bytes, and it lands about 6-13% slower. A better
  kernel cannot help a workload that is waiting on DRAM.

So the honest framing is not "Q4_0 is faster." It is: Q4_0 is what lets Arm's fast paths run at
all, which buys you time-to-first-token, and costs you a little decode.

## What this measures, and what it does not attribute

This experiment changes the quantization and measures the result. It does **not** isolate KleidiAI,
and the number above should not be read as "KleidiAI is worth +183%."

Moving from Q3_K_L to Q4_0 switches on **two** Arm optimizations at once:

1. KleidiAI's dotprod/i8mm matmul kernels, which exist only for Q4_0 and Q8_0, and
2. ggml's own Arm **repack** path (`ggml-cpu/arch/arm/repack.cpp`), which re-blocks Q4_0 weights
   into layouts the Arm dot-product instructions consume efficiently - and which is compiled in
   whether or not KleidiAI is.

Separating them needs a different experiment: hold the quantization at Q4_0 and build with
`GGML_CPU_KLEIDIAI` **ON** versus **OFF**. That experiment has not been run on this phone yet, so
this project does not claim the split.

So the +183% above is most likely the repack path and KleidiAI *together*, and this page does not
apportion it. The structural claim it is really about is unchanged and verified in Arm's kernel
source: **a K-quant reaches neither path, and nothing tells you.**

What has since been measured here is one level finer, and it complicates the picture in a way
worth stating plainly. Reading the tensor tables rather than the file-type label shows that a file
named `Q4_0` is only **76% Q4_0** - `token_embd.weight` is Q6_K and two `ffn_down` tensors are
Q4_1, so a quarter of the weights never reach KleidiAI whatever the label says.

Rebuilding the same model with that embedding promoted to Q8_0 raises coverage to 97% and made the
model **slower on both prefill and decode** on the reference phone. Coverage is not throughput.
The full measurement, the prediction it falsified, and the perplexity cost of every quantization
are in [`QUANTIZATION-QUALITY.md`](QUANTIZATION-QUALITY.md).

The advice to users does not change: if your model is not Q4_0 or Q8_0, you are leaving Arm's fast
paths on the table. What changes is that the app now reports the fraction of weights that reach
them instead of asserting a yes or no from the filename.

## How to check and fix your own app

1. **Read the quant off the model.** It is in the GGUF filename by convention
   (`...-Q4_0.gguf`) and in the GGUF header (`general.file_type`). If it is not `Q4_0` or `Q8_0`,
   KleidiAI is not running for that model, full stop.
2. **Prefer Q4_0 or Q8_0 when TTFT matters.** A long prompt is where the compute-bound GEMM
   dominates, and that is the gain KleidiAI actually delivers.
3. **Recommend, do not silently switch.** Q4_0 versus a similarly sized K-quant is also a quality
   tradeoff, not only a speed one. Surface the choice to the user rather than swapping their model
   under them. ENTITY does this with a model-info card advisor
   (`FileType.kleidiAiAccelerated`) that reads the header and reports whether the loaded model can
   reach KleidiAI and what it costs when it cannot. See [OPTIMIZATIONS.md](OPTIMIZATIONS.md) section 4.

## Limits

- One device measured (CMF Phone 1, Dimensity 7300, armv8.2-a+dotprod). The direction holds
  wherever KleidiAI's dotprod/i8mm kernels apply, but the exact percentages are specific to this SoC
  and this model. Newer Armv9 cores with i8mm or SME can widen the prompt gap further.
- The decode cost is a property of the byte count, not a KleidiAI weakness. A quant with fewer bytes
  than Q4_0 will decode faster and still miss the kernels.
- This is a sharp edge to document, not a criticism. KleidiAI does exactly what it advertises for the
  types it covers. The failure mode is a silent fallback for the types it does not, and the fix is to
  check the quant before you trust the acceleration.
