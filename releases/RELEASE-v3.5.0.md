# ENTITY v3.5.0 - prefill threading, core placement, LaTeX

**Status: built and release-signed** as `apk/ENTITY-v21-prefill-threads-placement-latex-20260723-release.apk`
(versionCode 18, cert SHA-256 `f34cd27c…`, identical to every release since v3.0.0 - so
`adb install -r` upgrades in place). Unit tests pass, including the release-variant suite.
**Not yet run on a device.** See *Verification* below.

## Why

Six contributed benchmark rows across four SoCs (Dimensity 7300, Tensor G5, SM8550, SM8450) showed
something the two development phones could not: **prompt processing was running on two threads on
every flagship.** A Dimensity 7300 prefills Llama-3.2-1B-Q4_0 at 139 tok/s; an SM8550 - stronger
silicon, with i8mm - manages 111.

Two causes compounded.

1. `top_cluster_core_count()` counts cores whose `cpuinfo_max_freq` is within 10% of the fastest.
   That is a proxy for "the performance cluster" which only holds on chips with no prime core. Every
   modern flagship puts its prime 17-20% above its own big cluster (3052/3782 = 80.7% on Tensor G5,
   2803/3360 = 83.4% on SM8550, 2496/2995 = 83.3% on SM8450), so the count collapsed to 1 and only
   `N_THREADS_MIN` pulled it back to 2.
2. Prefill inherited that number, because `n_pp = n_gen`.

Decode was never the problem. It is memory-bandwidth-bound and two threads already saturates it -
an SM8550 decodes 23.8 tok/s on 2 threads and 6.72 on 8 - so **the decode thread count `n_gen` is
deliberately unchanged.** Only the prefill width moves.

One caveat on "unchanged": the *pinned affinity mask* did widen, from `n_gen` cores to
`max(n_gen, n_pp)`, because ggml's workers inherit the calling thread's mask (see below). Decode
compute still runs on the `n_gen`-wide generation pool, so throughput should be unaffected - but
that is a reasoned expectation, not a measurement, and it is one of the things an on-device run
should confirm.

## What changed

### Prefill threading

`n_pp` is now derived from the performance cluster instead of copied from `n_gen`. Cluster detection
reads `/sys/devices/system/cpu/cpuN/cpu_capacity`, the kernel's own normalised per-core capacity
(1024 = strongest core, from `capacity-dmips-mhz` x max clock). Frequency alone cannot do this job:
an A55 at 2.0 GHz and an A78 at 2.5 GHz are 25% apart in clock and roughly 3x apart in throughput.
Kernels that omit `cpu_capacity` fall back to `cpuinfo_max_freq`.

The cluster rule is "strictly above the slowest tier". Unity's "at least twice the slowest core's
capacity" was tried first and rejected: it is calibrated for the capacity scale and breaks on the
frequency fallback, where no real SoC clocks its big cores at twice its little cores - a 4+4 device
would have had *no* core clear the bar, collapsing to the uniform-CPU fallback and widening prefill
across the A55s. The shipped rule gives the same answer under both signals. Against the four
contributed topologies:

| device | n_gen (unchanged) | n_pp (was = n_gen) |
|---|---|---|
| Dimensity 7300 | 4 | 4 |
| Tensor G5 | 2 | 6 |
| SM8550 | 2 | 5 |
| SM8450 | 2 | 4 |

The pinned CPU set now covers the wider of the two pools. `pin_to_fast_cores()` sets the *calling*
thread's affinity and ggml's workers spawn lazily inheriting it, so a set built for `n_gen` alone
would have confined the batch pool to the decode cores.

### Core placement is now the user's choice

Settings -> Inference -> **Core placement**: *Auto* / *Perf cores* / *Scheduler*.

Pinning is not a free win. Across the contributed dataset it swings from **-8.5% to +29.3%** on
decode, and in the median it slightly *reduces* tokens per watt - the Pixel 10 is the clean case,
+29.3% faster for +33.5% more power, so tok/W falls 3.2%. Android's own guidance is that forcing
affinity also stops the platform reacting to load and thermal throttling.

The ablation benchmark already runs threads-only and optimized at the same thread count, differing
only in affinity, so Settings reports which won **on this phone** and offers a one-tap apply. The
energy half of the verdict is suppressed for charging runs, whose watts are the charger's rather
than the workload's.

The benchmark's restore path used to hardcode `pinCores = true`, which would have silently re-pinned
a user who chose the scheduler. It now restores the setting.

### LaTeX in chat

Models emit LaTeX; it used to render as raw source. `$..$`, `$$..$$`, `\(..\)` and `\[..\]` are now
recognised and rendered - Unicode where Unicode suffices (`x^2` -> x², `\alpha \times \beta` ->
α × β, `H_2O` -> H₂O), custom Canvas spans where it does not: stacked fractions with a real rule,
radicals with a vinculum, display math centred on its own line.

Zero new dependencies, in the same hand-rolled spirit as `Markdown.kt`. Math is lifted out *before*
the emphasis pass, or the `*` in a `\times` expansion and the `_` in `x_1` would be eaten as
Markdown. Currency is not mistaken for math: `$5 and $10` stays text.

## Verification

| Claim | How |
|---|---|
| LaTeX scanner, symbol tables, script mapping, group parsing | 19 JVM unit tests, passing |
| Rest of the chat app still builds and passes | full `testDebugUnitTest`, passing |
| New C++ compiles and is warning-clean | standalone `g++ -Wall -Wextra` on the extracted functions |
| Thread widths on the four contributed topologies | simulation, **both** the capacity path and the `cpuinfo_max_freq` fallback, all four match under each |
| Uniform-CPU fallback | ran on a 12-core x86 host: capacity read, perf cluster = 12, `n_pp` capped at 6 |
| Release build + R8 | green; LaTeX symbol tables, placement strings and `cpu_capacity` verified present in the packaged APK and `libai-chat.so` |
| **On-device behaviour** | **not yet run** - APK built but not installed |

The `n_pp` value itself is a defensible default, not a measurement. The in-app thread sweep is what
should confirm the right width per device; running it on a Pixel 10 (where 6 threads is exactly the
big cluster) is the obvious next step.
