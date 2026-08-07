# Documentation

**Start at [`DOCUMENTATION.md`](DOCUMENTATION.md)** - it is the index, with a reading order.

| File | What it is for |
|---|---|
| [`DOCUMENTATION.md`](DOCUMENTATION.md) | **The index.** Reading order plus the current technical summary. |
| [`JOURNEY.md`](JOURNEY.md) | Every claim that had to be withdrawn, why it survived, and what replaced it. The falsification record. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module layout, threading model, file-by-file inventory. |
| [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md) | The algorithms - core selection, thread widths, deadline hints, context admission, thermal policy, power math - and the limit of each claim. The deepest file here. |
| [`KLEIDIAI-QUANTS.md`](KLEIDIAI-QUANTS.md) | Which quantizations reach which Arm kernels. |
| [`QUANTIZATION-QUALITY.md`](QUANTIZATION-QUALITY.md) | What each quantization costs in perplexity, how much of a file actually reaches KleidiAI, and the prediction about coverage that the measurement disproved. |
| [`BUILD.md`](BUILD.md) | Toolchain and build. Neither app builds standalone. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Conventions and validation. |
| [`FAQ.md`](FAQ.md) | Short answers, including what is deliberately *not* claimed. |
| [`CHANGELOG.md`](CHANGELOG.md) | Pointer to the canonical changelog at the repository root. |

## If you only read two

[`JOURNEY.md`](JOURNEY.md) for why any of this should be believed, and
[`OPTIMIZATIONS.md`](OPTIMIZATIONS.md) for what it actually does.

## Evidence lives elsewhere

Measurements are in [`../benchmarks/`](../benchmarks/), not here - starting with
[`../benchmarks/README.md`](../benchmarks/README.md) and
[`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md).

## Prefer to read it as a site

The same material, written to be read rather than audited, is at
**<https://kkjjkamal123.github.io/ENTITY-WEB/>** - animated explainers of each optimization, a
live device leaderboard over the contributed dataset, and the falsification record. Source:
[kkjjkamal123/ENTITY-WEB](https://github.com/kkjjkamal123/ENTITY-WEB).
