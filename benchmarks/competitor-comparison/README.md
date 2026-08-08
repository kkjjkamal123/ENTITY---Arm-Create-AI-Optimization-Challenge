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
| State | Unplugged, cooled 30 minutes between apps |
| Runs | 5 per app |
| Date | 2026-07-20 |

## Result

![Three-app comparison](three_app_comparison.png)

| App | Prompt (pp 512) | Token generation (tg 128) | Threads |
|---|---:|---:|---|
| PocketPal AI | 88.32 tok/s | 13.9 tok/s | 6 |
| **Arm AI Chat** (Arm's own app) | 121 ± 2.99 tok/s | 12.4 ± 0.0751 tok/s | not reported |
| **ENTITY** | **128 tok/s** | **18.2 tok/s** | 4, pinned |

**Against Arm's own reference app, on Arm's own silicon: +6% prompt, +47% token generation.**
Against PocketPal: **+45% prompt, +31% token generation.**

Decode is where ENTITY's policy acts, and decode is where the margin is. The prompt column is
close because all three apps are running Q4_0, which means all three are reaching the same KleidiAI
GEMM kernels — that column mostly measures whether an app got the quantization right, and here
everyone did.

## Why ENTITY wins, and why it is not a trick

The margin is not a mystery optimization. It is the thing ENTITY's own ablation measured: the
**thread count**. PocketPal runs **6 threads** on a 4+4 big.LITTLE chip, so threads 5 and 6 land on
Cortex-A55s at roughly a third of an A78's throughput and every decode step waits on them — the
same failure the naive 8-thread arm shows in [BENCHMARKS.md](../BENCHMARKS.md), just less severe.
ENTITY derives 4 threads from the device's live `cpufreq` ranking and keeps both phases off the
efficiency cores. Arm AI Chat does not report its thread count, so for Arm's app the mechanism is
inferred rather than measured.

The second factor is **KleidiAI**, and here it is a null result worth stating: all three apps are
running Q4_0, one of the only two types Arm's KleidiAI has kernels for, so nobody is being penalised
by the gate in this comparison. Load a K-quant into any of them and the prompt column collapses —
that is the finding in
[OPTIMIZATIONS §4](../../docs/OPTIMIZATIONS.md#4-quantization-is-what-gates-arms-kleidiai-kernels),
and it is why ENTITY tells the user which case they are in.

## The July session, and why both are published

The same three apps were measured on 2026-07-14. That session is kept here rather than replaced,
because the difference between the two is itself the result:

| App | 2026-07-14 prompt / decode | 2026-07-20 prompt / decode |
|---|---:|---:|
| PocketPal AI | 86.4 / 10.9 | 88.32 / **13.9** |
| Arm AI Chat | 120 ± 3.8 / 12.9 ± 0.08 | 121 ± 2.99 / **12.4 ± 0.0751** |
| ENTITY | 133 / 15.6 | 128 / **18.2** |

Three things moved, and two of them are inconvenient:

- **PocketPal and Arm swapped places.** In July Arm's app beat PocketPal on decode (12.9 vs 10.9);
  in the current session PocketPal beats Arm (13.9 vs 12.4). The July claim that PocketPal "comes
  last" no longer holds and has been removed rather than quietly left standing.
- **ENTITY's prompt margin over Arm narrowed**, from +11% to +6% — ENTITY read 133 then and 128 now,
  while Arm read 120 then and 121 now.
- **ENTITY's decode margin widened**, from +21% to +47%, consistent with the five-run four-arm
  exports that put ENTITY Auto at 18.1 tok/s on this phone.

PocketPal's decode swung about 27% between the two sessions on identical hardware and an identical
workload, while Arm's app held within about 4% — and PocketPal's swing is what flipped the ranking.
That is the argument for insisting on a matched session rather than the more flattering one:
**any of these numbers paired across dates would produce a margin the evidence does not support.**
The most inflated pairing available here is the current ENTITY 18.2 against July's PocketPal 10.9,
which reads +67% where the matched figure is +31%. Both sessions are dated, and neither is deleted.

The July screenshots remain below as the evidence for that session's row.

## Honesty notes

These are the caveats a reader is entitled to, stated up front rather than buried:

- **All three figures in the current table are medians over five runs**, each app running its own
  built-in benchmark at the same PP 512 / TG 128 settings, with a 30-minute cooldown between apps
  so no app inherits another's heat. No app's best run is used against another's median.
- **ENTITY's live-chat readout is deliberately not used.** The other two figures are synthetic
  PP/TG benchmarks. A live-chat token rate is a different quantity, and comparing the two is
  exactly what [COMPARISONS.md](../COMPARISONS.md) forbids.
- **PocketPal's config reports `GPU Layers: 99`** while producing a CPU result, and its model file is
  765 MB against the 773 MB file used by the other two. Its build or model copy may differ slightly —
  which is one candidate explanation for it moving 10.9 → 13.9 between sessions.
- Arm AI Chat does not report its thread count, so the "6 threads" explanation is established for
  PocketPal only. For Arm's app the mechanism is inferred, not measured.
- **The competitor figures are read off each app's own results screen**, which is all those apps
  expose. A reader can reproduce them by installing the same three apps; they cannot re-analyse
  them. ENTITY's figure comes from ENTITY Bench, whose per-run CSV export is the only raw evidence
  any of these three apps can produce.
- Single-device result. A different SoC may reorder these — and as the two sessions above show, so
  may the same device on a different day.

## Screenshots

Unedited, straight from each app. **These are the 2026-07-14 session** — the evidence for that
row of the table above, not for the current one:

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
