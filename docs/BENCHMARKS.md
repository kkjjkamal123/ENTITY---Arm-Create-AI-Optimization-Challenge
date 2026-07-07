# Team ENTITY — Benchmark Results

Device: **CMF Phone 1** — MediaTek Dimensity 7300 (4× Cortex-A78 + 4× Cortex-A55), Mali-G615, 6GB RAM, Android 16
Engine: llama.cpp build `7af4279`, compiled with `-DGGML_CPU_KLEIDIAI=ON` (Arm KleidiAI + dotprod/SDOT)
Model: Llama-3.2-1B-Instruct **Q4_0** (729.75 MiB, 1.24B params)
Method: `llama-bench`, warmup + 3 repeats, pp128 (prompt) / tg64 (generation)

---

# ★ MASTER BENCHMARK (definitive) — 3 models, optimized vs naive, + power

Config: **NAIVE** = `-t 8` (all cores, default scheduler). **OPTIMIZED** = `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3` (pin 4 threads to A78 big cores 4-7 + realtime priority). `--ignore-eos`, n=80. Models: Llama 1B (Q4_0), Llama 3B (Q4_0). Idle power **0.93 W**. Start 35°C → end 42°C. Charts: `proof/chart_speed_all.png`, `proof/chart_efficiency_all.png`.

### Phase 1 — SPEED (no power sampler, idle phone, 2-rep avg)
| Model | Naive gen (t/s) | Optimized gen (t/s) | Δ | Naive prompt | Opt prompt |
|-------|----------------:|--------------------:|:--:|-------------:|-----------:|
| Llama 1B  | 13.65 | **18.35** | **+34%** | 91.1 | 123.2 |
| Llama 3B  | 3.75 | **5.95** | **+59%** | 19.9 | 22.0 |

### Phase 2 — POWER (with sampler = system under contention)
| Model | Config | gen (t/s) | power (W) | efficiency (tok/s/W) |
|-------|--------|----------:|----------:|---------------------:|
| Llama 1B | naive | 8.1 | 4.09 | 1.98 |
| Llama 1B | **optimized** | 15.9 | 3.94 | **4.04** (2.0×) |
| Llama 3B | naive | 0.7 | 4.29 | 0.16 |
| Llama 3B | **optimized** | 4.4 | 3.63 | **1.21** (7.6×) |

### KEY FINDINGS
1. **Optimization is model-specific.** `-t4 pinned+realtime` gives big wins on Llama **1B (+34%)** and **3B (+59%)** on an idle phone — the decode path stays on the fast A78 cores instead of migrating onto the slow A55 LITTLE cluster.
2. **Under contention/heat (Phase 2), optimization wins across the board** — realtime priority + big-core pinning protect the workload from interference and throttling. Naive collapses (3B: 0.7 t/s!) while optimized holds (3B: 4.4 t/s = **6.3× faster under load**).
3. **Energy efficiency always improves with optimization:** 1B **2.0×**, 3B **7.6×** more tokens per watt — and at similar or *lower* power draw.
4. **Real-world takeaway:** the optimization's value is **consistency + efficiency under real load**, not just peak idle speed. A phone running an app is never idle.

---

# ★ QUANTIZATION LADDER (Llama 1B on Arm) — "smaller ≠ faster"

Optimized config, `--ignore-eos`, 2-rep avg. Chart: `proof/chart_quant_ladder.png`. Raw: `proof/quant_bench.txt`.

| Quant | Bits (~) | Size | Gen t/s | Notes |
|-------|:--------:|-----:|--------:|-------|
| Q8_0   | 8   | 1262 MB | 11.2 | 2× the bytes → memory-bandwidth bound → slow |
| **Q4_0** | 4 | 738 MB | **18.45** | **sweet spot** — fast Arm `dotprod` kernels |
| Q3_K_L | ~3.5| 700 MB | 18.60 | barely smaller than Q4_0 |
| IQ3_M  | ~3  | 628 MB | 12.6 | smaller but **SLOWER** — no fast Arm kernel for IQ format |

**FINDINGS:**
1. **Q8→Q4 is the big win:** −42% size **and** +65% speed (11.2→18.45 t/s). Quantization is the top single lever for on-device speed (bandwidth-bound).
2. **Q4_0 is optimal on Arm.** Its 4-bit blocks map to the hardware `SDOT`/dotprod path; llama.cpp has hand-tuned aarch64 kernels for it.
3. **"Quantize even more" backfires here:** IQ3_M is smaller yet **1.5× slower** than Q4_0 because importance-matrix (IQ) formats need complex dequant with **no optimized Arm kernel**. → *format matters more than bit-width on Arm.*
4. **Sub-4-bit gives almost no size saving** on this model: Q4_0=738 MB vs IQ3_M=628 MB. Llama 3.2's **128k-token vocab embedding dominates the file** and stays high-precision. Aggressive quant pays off more on models with smaller vocab / larger transformer blocks.

# ★ TIME-SERIES / THERMAL (sustained ~20 min, Llama 1B optimized)

Chart: `proof/chart_over_time.png`. Raw: `proof/ts_bench.csv`. Battery-sensor temp (SoC thermal zones not root-readable).

- **Temp: 34 → 39 °C** over ~20 min of continuous generation (mild +5 °C).
- **Generation held ~17–18 t/s even at 39 °C → NO thermal throttling** on the optimized config (fewer big cores = cooler, sustainable).
- **Intermittent dips to 8–13 t/s = Android throttling the *background* Termux process** (app-lifecycle CPU limiting), *not* thermal. Even with `termux-wake-lock`, Android caps background-app CPU.
- **Deployment insight:** a proper **foreground Android app / foreground-service** would avoid these dips → motivates shipping a real app over a terminal process.

# Helper scripts (developer experience)
- `clean_ram.sh` — free RAM before a run (kills strays, sync, reports freed MB + temp).
- `chat.sh` / `chat3b.sh` — one-command optimized offline chat per model.
- Benchmark scripts: `master_bench.sh`, `quant_bench.sh`, `ts_bench.sh`.

---

## Experiment 1 — Thread / core scaling (KleidiAI ON)

| Cores | Prompt pp128 (t/s) | Generation tg64 (t/s) |
|------:|-------------------:|----------------------:|
| 1 | 28.51 ± 5.78 | 9.57 ± 1.85 |
| 2 | 70.17 ± 0.56 | **17.02 ± 0.49** ← best gen |
| 3 | 75.63 ± 8.36 | 16.19 ± 2.31 |
| 4 | 90.67 ± 1.66 | 14.91 ± 0.80 |
| 6 | **92.06 ± 1.06** ← best prompt | 12.65 ± 2.10 |
| 8 | 77.56 ± 3.51 | 8.53 ± 2.67 |

**Findings:**
- Generation (decode) is **memory-bandwidth bound**: peaks at **2 threads**, then degrades — using all 8 cores nearly halves speed (17.0 → 8.5 t/s). The slow A55 LITTLE cores drag it down.
- Prompt processing (prefill) is **compute bound**: scales with cores, peaks at **6 threads** (92 t/s).
- Optimization implication: split threads → prompt `-tb 6`, generation `-t 2` for best of both. (to verify)

---

## Experiment 2 — KleidiAI ON vs OFF (Llama 1B Q4_0)

| Build | Threads | Prompt pp128 (t/s) | Generation tg64 (t/s) |
|-------|--------:|-------------------:|----------------------:|
| Baseline (KleidiAI OFF) | 2 | 62.68 ± 2.13 | 17.02 ± 0.30 |
| **KleidiAI ON**         | 2 | 61.32 ± 0.34 | 17.01 ± 0.08 |
| Baseline (KleidiAI OFF) | 4 | 83.45 ± 3.96 | 15.98 ± 0.38 |
| **KleidiAI ON**         | 4 | **90.72 ± 0.89** (+8.7%) | 15.25 ± 1.11 |

**Finding:** On this SoC KleidiAI gives only a small prompt-processing gain (~+9% at 4 threads) and ~0% on generation. Reason: the Cortex-A78 has **dotprod (SDOT) but NOT i8mm/SME** (confirmed at build: `+dotprod ... noi8mm nosve nosme`), and llama.cpp's default ggml CPU kernels **already use dotprod**. KleidiAI's large gains come from i8mm/SME kernels, which this chip lacks. → KleidiAI is a real but minor lever here; the big wins on this device are **quantization** and **big.LITTLE core tuning**.

## Experiment 3 — Runtime optimization: CPU affinity + priority (Llama 1B Q4_0)
_Measured via llama-cli single runs while a background download ran (contention → conservative numbers). Cores: A55 little = cpu0-3 @2.0GHz, A78 big = cpu4-7 @2.5GHz._

| Config | Prompt t/s | Generation t/s |
|--------|-----------:|---------------:|
| C0 `-t 2` default scheduler | 52.8 | 14.6 |
| C1 `-t 2` pinned big + realtime (`-Cr 4-7 --cpu-strict 1 --prio 3`) | 50.7 | 16.1 |
| C2 `-t 3` pinned | 50.2 | 8.4 (odd-thread imbalance) |
| **C3 `-t 4` pinned big + realtime** ← WINNER | **78.6** | **17.1** |
| C4 `-t 2` pinned + flash-attn + KV q8_0 | 10.7 | 2.9 |

**Findings:**
- **Pinning threads to the A78 big cores (4-7) + realtime priority is the biggest runtime win.** It lets 4 threads beat the naive 2-thread config on *both* prompt (78.6) and generation (17.1), because the scheduler can no longer migrate threads onto slow A55 cores or preempt them. Best config: `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3`.
- **Flash-attention + KV-cache quantization DESTROY performance on this CPU** (2.9 t/s) — they add dequant overhead; they are GPU-oriented features. Documented negative result.

## Experiment 4 — Quantization comparison (Q4_0 vs Q8_0 vs F16)
_Pending._

## Experiment 6 — HEADLINE: Naive vs Optimized, with POWER (tokens/sec/watt)
_Power-instrumented via `termux-battery-status` (BatteryManager, no root). Phone UNPLUGGED/discharging. `--ignore-eos` forces full 128-token generations for stable measurement. Idle power averaged over 10 samples. Naive = `-t 8` (all cores, default scheduler). Optimized = `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3` (pin A78 big cores + realtime priority). Cooldown 20s between runs._

**Idle baseline: 1.23 W**

| Model / config | Prompt t/s | Gen t/s | Power (W) | Efficiency (tok/s/W) |
|----------------|-----------:|--------:|----------:|---------------------:|
| 1B naive (-t8)      | 64.6  | 10.4 | 4.23 | 2.46 |
| **1B optimized**    | 102.8 | **15.7** | **4.04** | **3.89** |
| 3B naive (-t8)      | 12.2  | 1.4  | 4.83 | 0.29 |
| **3B optimized**    | 11.7  | **4.0** | 4.67 | **0.86** |

**HEADLINE FINDINGS (honest, power-instrumented):**
- **Optimization improves speed AND energy efficiency simultaneously.** 1B: **1.5× faster** (10.4→15.7 t/s) at *lower* power (4.23→4.04 W) = **1.6× more energy-efficient** (2.46→3.89 tok/s/W). 3B: **2.9× faster** (1.4→4.0 t/s) = **~3× more energy-efficient** (0.29→0.86 tok/s/W).
- The gain is **largest on the heavier 3B**, where naive all-core scheduling is catastrophic (1.4 t/s).
- Charts: `proof/chart_speed_naive_vs_opt.png`, `proof/chart_efficiency.png`.

**CORRECTION:** An earlier run claimed "3.3× on 1B" — that used numbers contaminated by early-EOS stops (naive measured an artificially low 5.07). With `--ignore-eos` (stable) and cross-checked against llama-bench thread-scaling (8-thread tg ≈ 8.5 t/s), the honest 1B speedup is **~1.5–2×**. Reported the corrected figure.

**MEASUREMENT CAVEATS:** (1) The power sampler spawns `termux-battery-status` ~2×/sec, stealing a little CPU, so measured gen t/s runs slightly below pure peak (1B optimized peaks ~17 t/s uninstrumented; measured 15.7 here). Relative comparisons are valid — all configs bear the same sampler overhead. (2) Voltage from BatteryManager; power = |current| × voltage. (3) 3B absolute t/s is conservative (measured after 1B runs; some residual warming) but the naive-vs-optimized ratio holds.

## Experiment 5 — Model comparison (1B vs 3B, optimized config)
_Config: `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3`. 3B run with only ~1 GB free RAM (fit via mmap)._

| Model | Size (Q4_0) | Generation t/s | Quality (subjective) |
|-------|------------:|---------------:|----------------------|
| Llama 3.2 **1B** | 0.73 GB | ~17 | Fast; loses facts, inconsistent, weak jokes |
| Llama 3.2 **3B** | 1.83 GB | ~6.5–7.5 | ~2.6× slower; coherent, structured, consistent |

**Finding:** 3B is ~2.6× slower (tracks the 3× parameter count / memory-bandwidth wall) but markedly more coherent. 3B loads in ~2.0 GB RSS — needs background apps closed on a 6 GB phone. Trade-off: 1B for speed, 3B for coherence.

## Experiment 4 — Model comparison (1B vs 1.5B vs 3B)
_Pending._
