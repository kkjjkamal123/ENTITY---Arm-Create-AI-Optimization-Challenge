# ENTITY v3.7.0 - predicting a model before downloading it

**Status: source-tagged, APK not yet published.** versionCode 22. Unit tests pass (72). A
release-signed APK will follow under the same cert as every release since v3.0.0, so
`adb install -r` will upgrade in place.

## The gap

The catalog ranked models on RAM and ISA flags. That answers "will it fit" and not "will it be
usable" - a 4 GB phone could be told a 3B model fits and still find it decodes at 3 tok/s. Learning
that costs a 2 GB download, which is the worst possible way to learn it.

And once a reply arrived, there was no way to see what it cost. The live stats bar reports the
current rate; nothing recorded what a *given* answer read and wrote.

## What shipped

**A device probe that needs no model.** The two phases of inference are bound by different things,
and both are measurable in about 60 ms:

- Decode reads essentially every weight once per token, so `tok/s ~= bandwidth / model_bytes`. That
  is a physical relationship, not a fitted curve. Bandwidth comes from a 32 MB `arraycopy` - large
  enough that no phone's last-level cache can hold it, so the number is DRAM rather than SRAM.
- Prefill is a GEMM over the whole prompt and tracks integer throughput on the performance cores,
  measured with a dependency-chained multiply-accumulate loop. Integer rather than float on purpose:
  a float benchmark would rank a phone with strong FP and weak integer units far too highly.

Both readings scale against one anchor device measured properly with `llama-bench`, which is also
what lets the estimate carry the Q4_0/Q8_0 trade rather than treating "reaches KleidiAI" as a single
good thing. It is an estimate and the screen says so - it cannot see thermal headroom, other apps'
memory pressure, or the vendor's scheduler.

**Token accounting per answer.** Long-press any reply: prompt tokens read, tokens written, both
rates, context used. Prompt and generation are never averaged into one number, because they are
produced by different regimes - averaging them is why speed claims about local models are so often
incomparable. Counts come from the native layer, since a partial multi-byte character emits an empty
piece and counting streamed tokens would undercount. Timings wrap the native calls only, so a slow
redraw cannot pass as a slow model.

**Fit judged against free memory.** A 6 GB phone with a browser and a few background apps resident
often has under 2 GB to give. Thresholds were re-derived rather than reused - fractions calibrated
against total RAM would reject models that genuinely run. Above 70% of free memory a model is
flagged tight rather than rejected, because weights are mmap'd page cache the kernel can evict,
while the KV cache is the anonymous memory that must actually fit. Both figures are shown.

## What broke first

Two of the probe's anchor constants were filled in by reasoning instead of by running the probe.
Bandwidth was set to 6.4 GB/s, derived from what decode actually achieves on that device; the probe
reads **26.2**, because a linear copy sees near-peak DRAM while decode walks many separate tensors.
The integer divisor was set to 2.6 ops/ns from what four Cortex-A78s ought to sustain; the real
figure is **1.042**, because a dependency-chained scalar MAC runs nowhere near a core's peak.

Decode estimates came out 4.1x too fast, prefill 2.5x too slow. On screen a 360M model was projected
at 251 tok/s, which is what made it obvious.

Nothing shipped in that state - but the feature was complete, reviewed, and passing all 72 unit
tests, because those tests assert internal consistency and both wrong constants were perfectly
consistent with themselves. Full record as entry 10 in `docs/JOURNEY.md`. The rule underneath is
narrow and worth stating: **a ratio is only dimensionless if both sides were measured the same
way.**

## Also

- Catalog expanded to 19 entries across seven vendors, 0.36B to 7B, tagged with vendor and role.
- Model cards report measured KleidiAI coverage from the tensor table, naming the largest tensor
  that misses, rather than asserting a boolean from the filename.
- `messages.stats` column added, schema 1 to 2. The previous `onUpgrade` was an empty stub.
- MEASURE AGAIN no longer overflows its background.
- A clean checkout builds again: `jvmToolchain(17)` was nested inside the `android` block, and the
  CMake version was pinned above what a stock Android SDK ships.
