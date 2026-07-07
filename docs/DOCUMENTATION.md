# ENTITY — Optimizing On-Device LLMs for Arm

**Track:** Mobile AI
**Device:** CMF Phone 1 — MediaTek Dimensity 7300 (4× Cortex-A78 @2.5 GHz + 4× Cortex-A55 @2.0 GHz), Mali-G615, 6 GB RAM, Android 16
**Thesis:** A mid-range, ~$200 Arm phone can run a private, fully-offline LLM assistant *smoothly* — if you optimize for the silicon instead of treating it like a small desktop. We show how, and we measure everything: speed, energy (tokens/sec/watt), thermals over time, and quantization trade-offs.

This document is the full record: **every command we ran, why we ran it, and what it bought us.** Anyone can reproduce the whole thing on an Arm64 device from this file.

---

## Why this is a big deal

Most on-device LLM demos just do `llama-cli -m model.gguf` and report a number. We treat the phone as what it is — an **asymmetric (big.LITTLE) Arm SoC with a power and thermal budget** — and optimize accordingly:

- **+34% to +59% generation speed** from big.LITTLE-aware core pinning + realtime scheduling (measured, not guessed).
- **Up to 7.6× better energy efficiency** (tokens per watt) — a metric almost nobody measures on-device.
- **A quantization finding that flips common intuition:** on Arm, *smaller is not faster* — Q4_0 beats a smaller 3-bit IQ quant by 1.5× because of kernel support.
- **Thermal reality:** the optimized config sustains full speed for 20 minutes with **no thermal throttling**, and we identified the *real* bottleneck for background inference (Android app-lifecycle throttling, not heat).

Every claim below has a command you can run and a number in `BENCHMARKS.md`.

---

## Part 1 — Full reproducible command log

### 1.1 Environment (on the phone, in Termux)

Termux is a Linux environment on Android. Install it from F-Droid or GitHub releases (**not** the Play Store build — it's outdated). Then:

```bash
termux-setup-storage                 # grant storage access
pkg update && pkg upgrade -y         # refresh Termux packages
pkg install -y git cmake make clang wget   # toolchain to build llama.cpp
pkg install -y openssh               # optional: drive the phone from a laptop over SSH
pkg install -y termux-api            # optional: read battery current/temp for power benchmarks
```

For long unattended runs, hold a wakelock so Android keeps the process alive:

```bash
termux-wake-lock
```

### 1.2 Build llama.cpp with Arm acceleration

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cd llama.cpp
cmake -B build -DGGML_CPU_KLEIDIAI=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j 4 --target llama-cli llama-bench
```

- `-DGGML_CPU_KLEIDIAI=ON` compiles in **Arm's KleidiAI** micro-kernels. On the A78 the build auto-selects `+dotprod` (the `SDOT` int8 instruction) and correctly disables `i8mm/SVE/SME` (this core doesn't have them). This is the Arm-specific code path.
- `-j 4` builds on the 4 performance cores.

### 1.3 Get the models (4-bit GGUF)

```bash
mkdir -p ~/models && cd ~/models
wget -O Llama-3.2-1B-Instruct-Q4_0.gguf \
  "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf?download=true"
wget -O Llama-3.2-3B-Instruct-Q4_0.gguf \
  "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf?download=true"
```

Quantization ladder for the size/speed study (1B):
```bash
for q in Q8_0 Q3_K_L IQ3_M; do
  wget -O Llama-3.2-1B-Instruct-$q.gguf \
    "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-$q.gguf?download=true"
done
```

### 1.4 Run it — the optimized launch command

```bash
cd ~/llama.cpp
./build/bin/llama-cli \
  -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf \
  -t 4 -Cr 4-7 --cpu-strict 1 --prio 3 \
  -c 4096 --color on \
  -sys "You are ENTITY, a helpful assistant running fully offline on a CMF Phone 1."
```

This single command line contains the core of our optimization — see Part 2 for what each flag does and why.

---

## Part 2 — The optimizations (what, command, why, impact)

### Optimization 1 — 4-bit quantization (Q4_0)
- **Command:** use the `...-Q4_0.gguf` model file.
- **Why:** generation is **memory-bandwidth bound** — the CPU spends most of its time waiting for model weights to arrive from RAM. 4-bit weights are ~4× smaller than FP16, so ~4× fewer bytes to move per token. Q4_0 specifically maps onto llama.cpp's hand-tuned aarch64 `dotprod` kernels.
- **Impact:** Q8→Q4 = **−42% size and +65% speed** (11.2 → 18.45 tok/s on the 1B).

### Optimization 2 — KleidiAI + dotprod build
- **Command:** `-DGGML_CPU_KLEIDIAI=ON` at build time.
- **Why:** Arm's int8 `SDOT` instruction does 4 multiply-accumulates in one step. KleidiAI provides micro-kernels that use it.
- **Impact (honest):** small on this SoC (~+9% prompt, ~0% generation) because the A78 has `dotprod` but not `i8mm/SME`, and llama.cpp's default kernels already use `dotprod`. KleidiAI's big wins need i8mm/SME (newer Armv9 cores). We keep it on and report the real number — it's a bigger lever on newer Arm hardware.

### Optimization 3 — big.LITTLE core pinning  ⭐ (our main lever)
- **Command:** `-t 4 -Cr 4-7 --cpu-strict 1`
- **Why:** the Dimensity 7300 is asymmetric — 4 fast A78 cores (cpu 4–7 @2.5 GHz) and 4 slow A55 cores (cpu 0–3 @2.0 GHz). By default the Android scheduler spreads threads across *all* cores, so the slow A55s become stragglers that the fast cores wait on. `-Cr 4-7 --cpu-strict 1` pins the 4 worker threads to the A78 cores only.
- **Impact:** avoiding the little cores is worth ~2× on generation (17.0 tok/s on 2–4 big cores vs 8.5 tok/s spread across all 8). We verified core layout with:
  ```bash
  for c in 0 1 2 3 4 5 6 7; do echo "cpu$c $(cat /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq)"; done
  ```

### Optimization 4 — realtime thread priority  ⭐
- **Command:** `--prio 3`
- **Why:** even pinned to big cores, a background process gets preempted by the OS. Realtime priority keeps the inference threads on-core and uninterrupted.
- **Impact:** on an idle phone it's a modest bump, but **under load/heat it's decisive** — it protects the workload from interference. In our contention test the naive config collapsed (3B → 0.7 tok/s) while the pinned+realtime config held (3B → 4.4 tok/s = **6.3× faster under load**).

### Optimization 5 — thread-count tuning
- **Command:** `-t 4` for generation (and optionally `--threads-batch 6` for prompt).
- **Why:** generation is bandwidth-bound (peaks at a few big cores), prompt processing is compute-bound (scales with cores). Using all 8 cores *hurts* generation. We swept `-t 1,2,3,4,6,8` with `llama-bench` to find the knee.
- **Impact:** correct thread count alone recovers ~2× vs the naive all-cores default.

### Combined optimized vs naive (measured)
| Model | Naive `-t 8` | Optimized `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3` | Gain |
|-------|-------------:|--------------------------------------------------:|:----:|
| Llama 1B | 13.65 | 18.35 tok/s | +34% |
| Llama 3B | 3.75 | 5.95 tok/s | +59% |

### What we tested and rejected (documented negatives)
- **Flash-attention + KV-cache quantization** (`-fa on -ctk q8_0 -ctv q8_0`): tanks CPU generation to ~3 tok/s. These are GPU-oriented; on the Arm CPU they add dequant overhead. **Off.**
- **Sub-4-bit quant (IQ3_M):** *smaller but slower* — 12.6 vs 18.45 tok/s — because IQ importance-matrix formats have no fast Arm kernel. **On Arm, quant format matters more than bit-width.**

---

## Part 3 — How we measured (methodology)

### Speed (clean)
```bash
./build/bin/llama-bench -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf -t 1,2,3,4,6,8 -p 128 -n 64 -r 3
```
For the optimized (pinned/realtime) configs we use `llama-cli` with `--ignore-eos` so every run generates the full token count — this removes early-stop noise and makes runs comparable.

### Power & energy efficiency (tokens/sec/watt)
No root needed — we read Android's BatteryManager via `termux-battery-status` (phone unplugged). Power = |current| × voltage:
```bash
termux-battery-status   # gives "voltage" (mV) and "current" (µA)
```
We sample during a run, average, and divide tokens/sec by watts. Idle draw was 0.93 W; under inference ~4 W. Efficiency gain from optimization: up to **7.6×** tok/s/W.

### Thermals over time
Battery-sensor temperature sampled each iteration during a 20-minute sustained run (SoC thermal zones aren't root-readable). Result: 34 → 39 °C, **no throttling** of the optimized config; the dips we saw were Android throttling the *background* Termux process — a real deployment insight (a foreground app avoids it).

All raw logs and charts are in `proof/`. Numbers and findings are in `BENCHMARKS.md`.

---

## Part 4 — What makes ENTITY unique

Things a typical hackathon entry would **not** do, that we did:

1. **big.LITTLE-aware scheduling as a first-class optimization** (core pinning + `--cpu-strict` + realtime), with the core layout auto-detected from `sysfs` — not just `-t N`.
2. **Energy efficiency measured in tokens/sec/watt** on real battery telemetry — turning "Mobile AI battery efficiency" from a talking point into a number.
3. **Arm quant-format analysis** proving `Q4_0 > IQ3` (format beats bit-width) — a genuinely counterintuitive, hardware-specific result.
4. **Sustained thermal/time-series profiling** and the finding that *the* limiter for background on-device inference is Android's app lifecycle, not heat.
5. **Rigor and honesty:** documented negative results (KleidiAI marginal here, flash-attn harmful), controlled for thermal confounds with cooldowns, and cross-checked tools (`llama-bench` vs `llama-cli`).

## Part 5 — Next-level unique optimizations

1. **Auto-tuner (DONE, `scripts/autotune.sh`)** — reads per-core max frequency from `sysfs`, detects the big-core mask, and emits the optimal `-Cr`/`-t`/`--prio` flags for *any* Arm phone. On this device it prints `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3` automatically. Portable across Snapdragon/Exynos/MediaTek — a developer-experience win that turns our findings into a tool.

2. **`--mlock` (VALIDATED)** — add `--mlock` to lock the model in RAM and prevent Android from paging it out under memory pressure (the cause of the stalls we saw when free RAM dropped). Confirmed it loads without a lock failure on this device.

3. **On-device speculative decoding (ANALYZED — RAM-gated on this device):** using the 1B as a *draft* to accelerate the 3B *target* (`llama-cli --model-draft`) is the dream mobile optimization, but it needs **both models resident**: 1B+3B ≈ 2.6 GB (or 0.5B+3B ≈ 2.2 GB) vs ~1.5–2.3 GB free on this 6 GB phone shared with Android. **It does not fit here.** Honest finding: *on-device speculative decoding is memory-gated on mid-range phones* — it becomes practical on 8–12 GB devices or with a sub-0.5 B draft. This is a real constraint worth reporting, not a failure to hide.

4. **System-prompt KV-cache reuse** — cache the fixed persona prompt so multi-turn chat skips recomputing it each turn (best implemented in the app layer / `llama-server`).
