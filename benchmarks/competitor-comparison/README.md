# Direct competitor comparison

ENTITY against the two most credible on-device LLM chat apps on Android, on the **same phone**, with
the **same model file**, running the **same workload**.

This is the comparison [COMPARISONS.md](../COMPARISONS.md) sets the bar for: same model
architecture, same weights, same quantization, same prompt and generated-token counts, same CPU
backend, same device. It clears that bar, which is why it is published here rather than as a
marketing line.

## Setup

| | |
|---|---|
| Device | Nothing A015 (CMF Phone 1), MediaTek Dimensity 7300, 4× Cortex-A78 + 4× Cortex-A55, 6 GB, Android 16 |
| Model | `Llama-3.2-1B-Instruct-Q4_0.gguf`, 1.24B params |
| Workload | PP 512 / TG 128, CPU backend |
| State | Unplugged, 36-37 °C at start |
| Date | 2026-07-14 |

## Result

![Three-app comparison](three_app_comparison.png)

| App | Prompt (pp 512) | Token generation (tg 128) | Threads |
|---|---:|---:|---|
| PocketPal AI | 86.4 tok/s | 10.9 tok/s | 6 |
| **Arm AI Chat** (Arm's own app) | 120 ± 3.8 tok/s | 12.9 ± 0.08 tok/s | not reported |
| **ENTITY** | **133 tok/s** | **15.6 tok/s** (median of 4 runs, range 14.4-16.4) | 4, pinned |

**ENTITY beats Arm's own reference app on Arm's own silicon: +11% prompt, +21% token generation.**
Against PocketPal: **+54% prompt, +43% token generation.**

## Why ENTITY wins, and why it is not a trick

The margin is not a mystery optimization. It is the two things ENTITY's own ablation measured:

1. **Thread count.** PocketPal runs **6 threads** and comes last. On a 4+4 big.LITTLE chip, threads 5
   and 6 land on Cortex-A55s, which are roughly a third of an A78's throughput, so every step waits
   on them. This is the same failure the naive 8-thread arm shows in
   [BENCHMARKS.md](../BENCHMARKS.md), just less severe. ENTITY derives 4 threads from the device's
   live `cpufreq` ranking and keeps both phases off the efficiency cores.
2. **KleidiAI.** All three apps happen to be running Q4_0 here, which is one of the only two types
   Arm's KleidiAI has kernels for. Load a K-quant instead and the prompt column collapses — that is
   the finding in [OPTIMIZATIONS §4](../../docs/OPTIMIZATIONS.md#4-quantization-is-what-gates-arms-kleidiai-kernels),
   and it is why ENTITY tells the user which case they are in.

A competitor's product being slower for exactly the reason ENTITY's ablation predicts is the
strongest confirmation the finding has.

## Honesty notes

These are the caveats a reader is entitled to, stated up front rather than buried:

- **ENTITY's decode is reported as the median of four runs with its full range (14.4-16.4), not its
  best.** Arm's app reports a 3-repetition result and PocketPal reports `Rep: 3`; putting our best
  run against their medians would not be a fair test. It is not needed either — **ENTITY's worst run
  (14.4) still beats Arm's 12.9.**
- **ENTITY's live-chat readout (16.9 tok/s) is deliberately not used.** The other two figures are
  synthetic PP/TG benchmarks. A live-chat token rate is a different quantity, and comparing the two
  is exactly what [COMPARISONS.md](../COMPARISONS.md) forbids.
- **PocketPal's config reports `GPU Layers: 99`** while producing a CPU result, and its model file is
  765 MB against the 773 MB file used by the other two. Its build or model copy may differ slightly.
- Arm AI Chat does not report its thread count, so the "6 threads" explanation is established for
  PocketPal only. For Arm's app the mechanism is inferred, not measured.
- Single-device result. A different SoC may reorder these.

## Screenshots

Unedited, straight from each app:

| App | Screenshot |
|---|---|
| Arm AI Chat | [`arm-ai-chat-llama-3.2-1b-q4_0.png`](arm-ai-chat-llama-3.2-1b-q4_0.png) |
| PocketPal AI | [`pocketpal-ai-llama-3.2-1b-q4_0.png`](pocketpal-ai-llama-3.2-1b-q4_0.png) |
| ENTITY (live chat) | [`entity-live-chat-llama-3.2-1b-q4_0.png`](entity-live-chat-llama-3.2-1b-q4_0.png) |

The ENTITY screenshot shows the live chat screen, not the benchmark, and its 16.9 tok/s is therefore
**not** the number used in the table above. It is included because it shows the same model running in
the shipped app with live power, temperature and CPU telemetry — which is a thing neither competitor
displays at all.

## Reproduce

```bash
python3 benchmarks/plot_competitors.py
```

Install all three apps, load the identical GGUF, and run each app's own benchmark at PP 512 / TG 128.
