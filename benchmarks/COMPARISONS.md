# Fair runtime comparison protocol

ENTITY's published Android-app result establishes an **in-app naïve-versus-Auto** comparison. It
is not a head-to-head result against another runtime. This page defines the evidence required
before publishing that stronger claim.

## Status

No result versus upstream llama.cpp, ExecuTorch, or MLC-LLM is published here yet. The repository
contains the runnable upstream llama.cpp baseline script below so that the first result can be
captured reproducibly rather than reported from unlike-for-like settings.

## Upstream llama.cpp baseline

Use the same phone, the same exact GGUF file, a cool unplugged state, and the same PP 512 / TG 128
workload as ENTITY's app benchmark. On the phone in Termux, run:

```bash
bash scripts/benchmark_vanilla_llama_cpp.sh \
  ~/models/Llama-3.2-1B-Instruct-Q3_K_L.gguf benchmarks/results
```

The script runs three individually retained `llama-bench` JSON passes with eight threads and normal
priority. It deliberately does **not** pass `-Cr`, `--cpu-strict`, or `--prio 3`: those change
scheduler placement and would no longer be a vanilla baseline. It records the llama.cpp version,
device properties, and the model SHA-256 beside the JSON.

Then run ENTITY's in-app **Benchmark** with three runs and export its CSV. Commit both evidence
sets with the date, phone, Android build, ambient/start temperatures, and model hash. Report prompt
tok/s and decode tok/s separately; do not compare a CLI's raw token-generation figure to a live
chat measurement.

### What this comparison can say

It can measure the cost or benefit of ENTITY's Android integration and Auto policy against a
default-scheduler upstream CPU run on the same device. It cannot isolate every difference between
the app's runtime build, Android JNI boundary, backend dispatch, and scheduling policy.

## In-app ablation

The controlled ablation this page used to only ask for now ships inside the app's Benchmark screen,
so it needs no second toolchain and no Termux. The benchmark runs three arms:

| Arm | Threads | Affinity | What it represents |
|---|---|---|---|
| Naïve | 8 | none | The out-of-the-box default: every core, scheduler-placed. |
| Threads only | Auto's count (2-4) | none | An upstream llama.cpp `-t N` run: the right thread count, no pinning, no pinned pool. |
| ENTITY Auto | Auto's count (2-4) | pinned to the frequency-ranked fast cores | The shipped path: both phases on the fast-core set. |

Naïve versus threads-only isolates the thread-count decision. Threads-only versus Auto isolates
core pinning. Both the decode and prompt rows are clean, because every arm runs both phases on the
same thread count. (Before v2.1.0 Auto widened prompt processing to every core, which made the
prompt row a confound — and, when finally measured, turned out to be a regression: prompt is 135
tok/s on 4 fast cores against 86 spread across all 8.) See [BENCHMARKS.md](BENCHMARKS.md).

Threads-only is not a stand-in for the upstream baseline script below — it shares ENTITY's runtime
build and JNI boundary. It isolates the *policy*; the script isolates the *stack*. Run both.

Implementation: `pinCores` in
[`ai_chat.cpp`](../app/entity.android/lib/src/main/cpp/ai_chat.cpp) skips `sched_setaffinity` and
the pinned thread pool, and clears any mask inherited from the previous arm; the arm order and
per-pass capture are in
[`BenchmarkActivity.kt`](../app/entity.android/app/src/main/java/com/example/llama/BenchmarkActivity.kt).
The result is published: the thread count earns the decode multiplier on every device measured
(+39% to +106%), and what the pinning adds is device-dependent - +21% decode on the Dimensity 7300
in the current five-run exports, +1% decode but ~30% lower median power on the Snapdragon 6 Gen 4;
July's three-run sets read it at ~0%. See [BENCHMARKS.md](BENCHMARKS.md).

## ExecuTorch and MLC-LLM

Do not add a comparison just because the runtime name is recognizable. A valid head-to-head run
must use the same model architecture, weights, quantization/precision, prompt and generated-token
counts, context/KV-cache type, CPU-vs-GPU backend, thread policy, device state, and repeated-run
statistic. GGUF is a llama.cpp format; ExecuTorch and MLC-LLM normally need separately converted or
compiled artifacts. A result with different model conversion or a GPU delegate measures the whole
deployment stack, not only ENTITY's CPU policy.

If one of those runtimes is available for the exact model on the same phone, retain its native log
and record the conversion command, runtime version, backend, and all settings beside the ENTITY
CSV. Otherwise, the upstream llama.cpp baseline above is the honest and immediately useful
comparison.

## Arm Performix scope

Arm Performix is valuable for hotspot and hardware-counter evidence, but its current documented
target is an Arm Linux system reached over SSH—not an Android handset. Therefore a Performix run on
an Arm Linux server must be labeled **server profiling** and must not be presented as a CMF/OPPO
mobile benchmark. It can still strengthen the optimization story by profiling a matching
`llama-bench` workload before and after a focused native change, retaining the hotspot report and
the exact binary/source revision.

For the Android app, the appropriate evidence remains an on-device benchmark export plus Android
profiling tools that can attach to the handset. Do not claim an Arm Performix result until a
supported Arm Linux target and the captured report are available.
