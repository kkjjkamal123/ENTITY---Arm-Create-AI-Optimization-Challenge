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
is proposed in [llama.cpp PR #25701](https://github.com/ggml-org/llama.cpp/pull/25701).) So a
benchmark on a K-quant can run for months while every person involved believes KleidiAI is doing
the work, and it never executed once.

## The measured cost

CMF Phone 1, MediaTek Dimensity 7300, Llama-3.2-1B, PP512, same four-thread unpinned config. The
only variable is the quantization.

| | Q3_K_L (733 MB, generic ggml) | Q4_0 (773 MB, KleidiAI) | Change |
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

So the honest framing is not "Q4_0 is faster." It is: Q4_0 is what lets Arm's kernels run at all,
which buys you time-to-first-token, and costs you a little decode.

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
