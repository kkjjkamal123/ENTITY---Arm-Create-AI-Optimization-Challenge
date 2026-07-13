# Project scripts

This folder contains every checked-in shell helper used while developing, tuning, and measuring
ENTITY. They are **Termux / llama.cpp CLI utilities**, not part of the Android APK and not required
for normal app use. The shipped app exposes its own benchmark at **⋮ → Benchmark**.

The scripts target the CMF Phone 1 / Dimensity 7300: four Cortex-A78 performance cores (`4–7`)
and four Cortex-A55 efficiency cores (`0–3`). Several optimized commands request CLI realtime
priority with `--prio 3`; that is historical CLI experimentation and is not a capability claimed by
the Android app.

## Prerequisites

- Termux on the phone, with `bash`, `coreutils`, `grep`, `awk`, and `procps` available.
- A llama.cpp checkout at `~/llama.cpp`, built with `llama-cli` at `./build/bin/llama-cli`.
- GGUF models in `~/models/` using the filenames shown below.
- `termux-api` for [`thermal_bench.sh`](thermal_bench.sh), because it reads battery temperature.

Run a script from Termux with `bash scripts/<script>.sh`, or copy it to the phone and run it from
there. Adjust model paths, prompts, and flags for a different device or llama.cpp build.

## Script inventory

| Script | Purpose | Key configuration / output |
|---|---|---|
| [`autotune.sh`](autotune.sh) | Detects the fastest CPU cluster from `cpufreq` and prints suitable llama.cpp flags. | Optionally accepts a model path and starts optimized 1B chat. |
| [`clean_ram.sh`](clean_ram.sh) | Stops stray `llama-cli` / `llama-bench` processes and reports free memory before and after. | Useful before a controlled run. |
| [`chat.sh`](chat.sh) | Starts optimized 1B Q4_0 chat. | `-t 4 -Cr 4-7 --cpu-strict 1 --prio 3 --mlock`, context 4096. |
| [`chat-naive.sh`](chat-naive.sh) | Starts naïve 1B Q4_0 chat for comparison. | `-t 8`, context 4096. |
| [`chat3b.sh`](chat3b.sh) | Starts optimized 3B Q4_0 chat. | Four performance cores, context 1024. |
| [`chat3b-naive.sh`](chat3b-naive.sh) | Starts naïve 3B Q4_0 chat for comparison. | `-t 8`, context 1024. |
| [`benchmark.sh`](benchmark.sh) | Compares naïve and optimized generation speed for 1B and 3B Q4_0. | Fixed prompt, 80 generated tokens, prints tok/s per model/configuration. |
| [`quant_bench.sh`](quant_bench.sh) | Compares 1B quantizations. | Emits CSV: `quant,size_MB,gen_tps` for available Q8_0, Q4_0, Q3_K_L, and IQ3_M models. |
| [`thermal_bench.sh`](thermal_bench.sh) | Runs sustained optimized 1B generation while logging speed and battery temperature. | Writes `~/thermal.csv` with 26 iterations. |

## Model filenames expected by the scripts

```text
~/models/Llama-3.2-1B-Instruct-Q4_0.gguf
~/models/Llama-3.2-3B-Instruct-Q4_0.gguf
~/models/Llama-3.2-1B-Instruct-Q8_0.gguf
~/models/Llama-3.2-1B-Instruct-Q3_K_L.gguf
~/models/Llama-3.2-1B-Instruct-IQ3_M.gguf
```

## Benchmark scope and current evidence

These scripts produced the historical Termux/CLI experiments preserved in
[`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md) and
[`../benchmarks/termux_master_results.txt`](../benchmarks/termux_master_results.txt).

For the current judge-facing result, use the single
[`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md) record. It distinguishes the
screenshot-backed Android-app measurement from the historical CLI experiments, so unlike-for-like
results are not mixed together.
