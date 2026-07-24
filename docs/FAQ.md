# ENTITY FAQ

[Home](../README.md) · [Benchmarks](../benchmarks/BENCHMARKS.md) · [Optimization](OPTIMIZATIONS.md) · [Starter kit](../templates/arm64-android-runtime/README.md) · [Contributing](CONTRIBUTING.md) · [License](../LICENSE)

## Does ENTITY need internet access?

No. Model loading, prompt processing, generation, chat storage, and runtime metrics run locally on
the phone. ENTITY does not require an inference server or a cloud API, and once a model is on the
device it runs with no connection at all.

From v3.2.0 the app does declare the `INTERNET` permission, for one optional feature: the model
catalog, which downloads a `.gguf` from a public model host when you tap one. It runs only on that
tap. If you import your own model from storage instead, nothing in ENTITY ever opens a connection.

## Which devices are supported?

The current release targets arm64 Android phones running Android 13 or later. It ships seven Arm
CPU backend variants and selects the strongest variant supported by the device at runtime. The
release does not include x86 or x86_64 binaries.

## Which models can I use?

Pick one from the in-app catalog, which lists models sized for your phone and says which reach Arm's KleidiAI
kernels, or import any runnable GGUF through the in-app document picker. Model weights are not bundled
with the APK. A 1B model is a good starting point; a 3B class model needs more free memory and may
receive a smaller Auto context window.

## What does Auto mode change?

Auto mode ranks online CPU cores by their advertised maximum frequency for decode (2 to 6 cores,
clamped), and - since v3.5.0 - ranks cores by the kernel's own per-core capacity for prompt
processing, which is usually wider and never narrower than decode's set. It sizes context from
model size plus available memory, and enables the thermal guard.

## Why not use every CPU core?

Because it is measurably slower — for **both** phases, which was a surprise.

Decode is limited by memory bandwidth, and on a big.LITTLE phone the efficiency cores gate the
token-by-token path: 8 threads gives 8.8 tok/s, 4 threads gives 16.9.

Prompt eval is compute-bound, so ENTITY used to widen it to all 8 cores. That was a regression: an
A55 is about a third of an A78's throughput, so every GEMM waited on the stragglers. Prompt on 4
fast cores measures 135 tok/s; across all 8 it measures 86. From v2.1.0 both phases ran on the same
fast-core set as decode; since v3.5.0 prompt processing derives its own, usually wider set instead
(see "Why does the app use two different thread counts?" below) - decode's clock-ranked set can
collapse to as few as 2 cores on a prime-core flagship, and prompt processing inheriting that same
narrow width was itself a later regression, fixed in v3.5.0.

## How does ENTITY avoid running out of memory?

Auto mode uses the GGUF file size and free RAM to choose a 2048, 4096, or 8192 token context. It
reduces KV-cache pressure before a larger model makes the app unstable. Manual mode leaves the
context decision to the user.

## Is a smaller quantization always faster?

No — and on Arm this is the single most expensive thing to get wrong.

**Arm's KleidiAI ships matmul kernels for Q4_0 and Q8_0 only.** Every other quantization, including
the entire K-quant and IQ family, falls back to generic ggml no matter which CPU backend variant the
app loaded. A Q3_K_L model is smaller on disk than Q4_0 and still leaves Arm's kernels completely
idle.

Measured on a Dimensity 7300, same phone and thread config, only the quant differing:

| | Q3_K_L (733 MB) | Q4_0 (773 MB) |
|---|---:|---:|
| Prompt throughput | 42.7 tok/s | **121 tok/s** |
| Time to first token | 12.1 s | **4.3 s** |
| Decode throughput | 16.9 tok/s | 14.7 tok/s |

Prompt eval is a compute-bound GEMM, which is what KleidiAI accelerates. Decode is bandwidth-bound
and tracks bytes-per-weight, so the slightly larger Q4_0 is slightly slower there. ENTITY's
model-info card tells you which case you are in when you load a model.

## Are power and efficiency numbers trustworthy while charging?

No. Charging changes the battery-current reading, so ENTITY hides power and tokens-per-watt during
charging. For comparable results, unplug the phone, let it cool, and run the same model and test
configuration.

## What do the published benchmark numbers mean?

They compare a naïve eight-thread configuration with the same Auto path used by chat. The current
record uses Llama 3.2 1B Instruct Q3 K L with PP 512 and TG 128. Read the full method, results,
and limits in [BENCHMARKS.md](../benchmarks/BENCHMARKS.md).

They are the gain of the shipped configuration over the out-of-the-box default. They do not say
how much of it comes from core pinning as opposed to simply using fewer threads, because those two
arms change both at once.

## So how much does the core pinning actually earn?

**Most of the gain is the thread count, not the pin - but the pin is real and device-dependent,
not zero.**

The Benchmark screen runs a third arm — threads-only: Auto's thread count with affinity switched
off, which is what an upstream `llama.cpp -t 4` run does. Across twelve runs on two models, dropping
8 threads to 4 earns +81% to +106% of decode, roughly 2x, and that is the larger share everywhere.
On three runs per arm the pin measured at ~0% on top of that - the first published answer here, and
too strong. A later four-arm, five-run export on two vendors' silicon refined it: **+21% decode on
the Dimensity 7300**, **+1% decode but a real power saving on the Snapdragon 6 Gen 4**. See "Can I
turn core pinning off?" below for the full range across the contributed dataset.

The affinity code ships pinning ON by default because it wins on the reference device, but it is
credited as a second-order, device-dependent effect - not the multiplier - and it is a setting, not
an assumption. Run the benchmark on your own phone and the numbers are yours:
[full record](../benchmarks/BENCHMARKS.md).

## Does ENTITY use realtime scheduling or root-only controls?

No, and it cannot. An unprivileged Android app has no access to kernel tunables: it cannot set a
cpufreq governor, change scheduler policy, write to `/proc/sys`, load a module, or raise itself to
a realtime priority class. Those paths need root, and requiring root would make the app
un-shippable and the measurements unrepresentative of a normal phone.

What ENTITY actually uses is the sanctioned unprivileged surface: `sched_setaffinity` for core
placement, ggml thread pools with explicit CPU masks, thread priorities, and a cooperative thermal
delay driven by `PowerManager.getCurrentThermalStatus()`. Everything it reads from the kernel -
`cpu_capacity`, `cpuinfo_max_freq`, `scaling_cur_freq`, battery current - is read-only.

Since v3.6.0 it also opens an **Android performance hint session** (ADPF). That is the one
sanctioned route an unprivileged app has into the kernel's scheduler and cpufreq machinery:
`sched_setaffinity` says *where* work runs but cannot say *how fast it needs to be*, so the kernel
still picks a frequency by reacting to load after the fact. A hint session declares the deadline
for each decode step instead, and the framework hands that to the same subsystems the vendor
already tuned - so it can raise clocks, place threads, and back off under thermal pressure, per
device, with no heuristic of ours.

It is shipped to be measured, not assumed: ENTITY Bench carries an `adpf` arm, and no speed or
energy claim is made for it until devices report back.

Historical Termux experiments with realtime priority are kept separate and are not claimed as app
behaviour.

## Does ENTITY render LaTeX?

Yes, since v3.5.0. Models emit LaTeX and it used to show as raw source. `$..$`, `$$..$$`,
`\(..\)` and `\[..\]` are recognised: symbols and super/subscripts map to Unicode where Unicode
has them (`x^2` -> x2, `\alpha \times \beta` -> alpha times beta, `H_2O`), and constructs that
need real two-dimensional layout - fractions, radicals - are drawn on a Canvas with a proper rule
and vinculum. Display math is centred on its own line.

No dependency was added; it is hand-rolled in `Latex.kt` the same way `Markdown.kt` is. Currency is
not mistaken for maths, so "it costs $5 and $10" stays text.

## Can I turn core pinning off?

Yes, since v3.5.0: Settings -> Inference -> **Core placement**, with *Auto*, *Perf cores* and
*Scheduler*.

It is a setting rather than a default because the measurements do not support one answer. Across
the contributed dataset, pinning ranges from **-8.5% to +29.3%** on decode depending on the phone,
and in the median it slightly *reduces* tokens per watt - it buys speed and pays for it in power.
Android's own guidance is that forcing affinity also stops the platform reacting to load and
thermal throttling.

So the app measures instead of asserting. The benchmark's threads-only and optimized arms already
run the same thread count and differ only in affinity, so after a run Settings reports which won on
*your* phone and offers one-tap apply. The energy half of that verdict is suppressed if the run was
charging, because a charging phone reports the charger's current rather than the workload's.

## Why does the app use two different thread counts?

Because decode and prefill are bound by different things. Decode is memory-bandwidth-bound and
saturates on a couple of cores; prompt processing is compute-bound and scales with width. Until
v3.5.0 both used one number, which was correct on a 4+4 phone and wrong on any phone with a prime
core - prefill was running on two threads on every flagship. See
[../benchmarks/CONTRIBUTED-DATA.md](../benchmarks/CONTRIBUTED-DATA.md).

## Where can I see the full optimization algorithm?

[OPTIMIZATIONS.md](OPTIMIZATIONS.md) documents the native implementation, core selection, context
admission, thermal policy, power math, benchmark statistics, and the limits of each claim.

## How can I contribute or report an issue?

Read [CONTRIBUTING.md](CONTRIBUTING.md) for build validation, project conventions, and next steps.
The canonical source is [kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge](https://github.com/kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge).

## Can I reuse the Arm runtime logic in another Android project?

Yes. The [Arm64 Android starter kit](../templates/arm64-android-runtime/README.md) contains the
pure Kotlin runtime policy, a portable C++ affinity helper, and a retargeting checklist. It is a
starting point, not a replacement for testing on the target phone.
