# ENTITY v2.0.0 benchmark record

This is the one canonical benchmark document for ENTITY. It reports the benchmark path that ships
in the Android app. The public source is [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

## What was measured

The in-app benchmark loads `Llama-3.2-1B-Instruct-Q3_K_L` and runs a synthetic llama-bench
workload of PP 512 and TG 128. Each configuration runs three times on an unplugged phone. Values
are median plus population standard deviation.

| Path | Configuration |
|---|---|
| Naïve | Eight threads across all online CPU cores. |
| Threads only | The same thread count Auto derives, with affinity off: no pinning, no pinned thread pool, placement left to the Linux scheduler. This is what an upstream llama.cpp `-t N` run does. |
| ENTITY Auto | CPU cores are ranked by maximum frequency. Decode uses the fastest two to four cores while prompt processing can use all online cores through the split thread pools. |

### Why three arms

Naïve and Auto differ in two variables at once: thread count and core placement. A two-arm result
therefore cannot say which one earns the speed-up, and a reader is entitled to assume the honest
answer is "mostly the thread count" — dropping from eight threads to four stops the little cores
from gating every decode step, and any user who passes `-t 4` to llama.cpp already has that.

The threads-only arm holds the thread count at Auto's value and removes only the affinity. The
decode gap between threads-only and Auto is therefore the value of pinning alone, and the gap
between naïve and threads-only is the value of the thread-count decision alone. The app prints both
attributions under the results table.

**Decode is the isolated comparison; prompt throughput is not.** Auto widens prompt processing to
every core through its split pools, and the threads-only arm cannot, so the prompt row mixes thread
count with placement by design. Read the decode row for the ablation.

The benchmark samples battery current and voltage during every pass. `PowerMath` resolves OEM
microamp versus milliamp reporting before calculating watts and tokens per watt. It hides power
and efficiency when the phone is charging. TTFT is a benchmark-derived estimate from prompt
evaluation plus one decode step, not a live-chat first-token measurement.

## Results

The tables below are the **two-arm v2.0.0 record**: they were measured before the threads-only arm
existed, so they report naïve versus Auto only. The change column is Auto over naïve, which is the
end-to-end gain of the shipped configuration over the out-of-the-box default. It is **not** an
attribution to core pinning; that requires the three-arm run described above and recorded as
[pending](#pending-the-three-arm-attribution) below. No threads-only number is estimated or
back-filled here.

| Device | Metric | Naïve | ENTITY Auto | Change |
|---|---|---:|---:|---:|
| CMF Phone 1, Dimensity 7300 | Prompt throughput | 42.2 ± 0.34 tok/s | 43.2 ± 1.8 tok/s | +2% |
|  | Decode throughput | 8.0 ± 1.1 tok/s | 17.7 ± 0.56 tok/s | +121% |
|  | Derived TTFT | 12,245 ± 108 ms | 11,907 ± 452 ms | 3% lower |
|  | Power | 4.7 ± 0.34 W | 4.0 ± 0.22 W | lower |
|  | Energy efficiency | 1.7 ± 0.36 tok/W | 4.2 ± 0.23 tok/W | 2.5× |
| OPPO CPH2729, Snapdragon 6 Gen 4 | Prompt throughput | 39.3 ± 2.2 tok/s | 47.7 ± 0.12 tok/s | +21% |
|  | Decode throughput | 6.0 ± 1.1 tok/s | 13.1 ± 0.05 tok/s | +117% |
|  | Derived TTFT | 13,194 ± 672 ms | 10,811 ± 28 ms | 18% lower |
|  | Power | 3.4 ± 0.15 W | 3.4 ± 0.29 W | flat |
|  | Energy efficiency | 1.8 ± 0.24 tok/W | 3.8 ± 0.31 tok/W | 2.1× |

|![Current in-app benchmark Mediatek](../screenshots/Benchmark.png)| |![Current in-app benchmark Snapdragon](../screenshots/Benchmark2.png)|

## Pending: the three-arm attribution

The app now ships the threads-only arm, but **no three-arm result is published yet.** The table
below is the shape of the result, not a claim; it is filled in from a device run, and no cell is
estimated in the meantime.

| Device | Naïve decode | Threads-only decode | Auto decode | Thread count earns | Pinning earns |
|---|---:|---:|---:|---:|---:|
| CMF Phone 1, Dimensity 7300 | 8.0 tok/s | pending | 17.7 tok/s | pending | pending |
| OPPO CPH2729, Snapdragon 6 Gen 4 | 6.0 tok/s | pending | 13.1 tok/s | pending | pending |

Both outcomes are publishable and neither is a failure:

- If threads-only lands close to Auto, the honest headline is that ENTITY's win comes from deriving
  the right thread count per device automatically, which a normal phone user never does by hand.
  The affinity policy is then a smaller refinement and is reported as one.
- If Auto stays clearly ahead of threads-only, the frequency-ranked pinning is carrying real weight
  and the claim is proven by the exact experiment a skeptical reader would demand.

Publishing a number without this arm would mean claiming affinity for a gain the thread count may
have earned. The arm exists so that the claim can be attributed rather than assumed.

## Interpretation and limits

The repeatable result is that ENTITY's Auto configuration improves both speed and energy efficiency
over the eight-thread default on two Arm SoCs with the same app, model, and test protocol. It does
not establish a universal performance multiplier for every phone, model, quantization, temperature,
or background workload, and, until the three-arm run above is published, it does not attribute the
gain to core pinning rather than to the thread count. The numbers are benchmark values rather than
live multi-turn-chat speed.

## Reproduce

Follow the exact [reproducibility protocol](REPRODUCIBILITY.md). In short: install the current
APK, load the stated Q3_K_L model, unplug and cool the phone, select three runs in **Benchmark**,
then export the CSV. The app performs a discarded warm-up, then runs naïve, threads-only and Auto
in that order, records every pass, and applies the same thermal cooldown before each pass so the
ordering does not favour the last arm.

### Evidence status
    
[`device-result-template.csv`](device-result-template.csv) is the machine-readable summary behind
the two published tables. It intentionally marks its `raw_csv_path` fields as `not-recorded`:
the original per-pass Android CSV exports for the v2.0.0 reference runs were not retained, so they
cannot be reconstructed honestly from a median and standard deviation. The checked-in
[`proof-logs/entity_results.txt`](proof-logs/entity_results.txt) and
[`termux_master_results.txt`](termux_master_results.txt) are historical CLI evidence with a
different workload and, in the optimized CLI case, realtime priority; neither is substituted for
the app result.

New app exports contain per-pass values plus app/device/ABI/version, benchmark order, warm-up and
cooldown provenance, and the per-arm affinity policy. Commit those CSVs beside a new device-result
row when contributing a result.

A fresh three-arm run therefore closes two gaps at once: it produces the attribution above, and its
export is the retained per-pass CSV the v2.0.0 reference runs never kept.

## Contribute a device result

Use [device-result-template.csv](device-result-template.csv) for a result from another arm64
Android phone. Keep the model, app version, selected backend, thermal starting point, raw CSV, and
test settings with the row. This makes a community result comparable without claiming that every
SoC will produce the same multiplier.

## Historical command-line data

[`termux_master_results.txt`](termux_master_results.txt) retains the original Termux CLI output.
It uses a different workload and includes CLI-only realtime priority in its optimized path. It is
useful background evidence but is not part of the Android app performance claim above.
