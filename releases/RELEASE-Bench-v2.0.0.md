# ENTITY Bench v2.0.0 - measures what the chat app now ships

**Status: built and release-signed** as `apk/ENTITY-Bench-v2.0.0-release.apk` (versionCode 9,
same cert as every prior release, so `adb install -r` upgrades in place). Unit tests pass,
including the release-variant suite. **Not yet run on a device.**

No user-facing features. The bench app benchmarks and uploads - that is all it does. This release
exists because the chat app's inference configuration changed in v3.5.0, and the bench app's
`optimized` arm is only meaningful if it measures the configuration the chat app actually ships.

## What changed

### Same inference path as chat v3.5.0

`ai_chat.cpp` takes the identical change: `cpu_capacity`-based cluster detection with a
`cpuinfo_max_freq` fallback, capacity-ranked pinning order, and prefill thread width (`n_pp`)
decoupled from decode width (`n_gen`) at both threadpool attach sites.

`n_gen` is unchanged, so **decode numbers stay comparable with every result already in the
dataset.** The `optimized` and `threads_only` arms' prefill and TTFT figures will move on
prime-core SoCs, because those arms were previously prefilling on 2 threads. Decode, watts and
tok/W are unaffected by construction.

The efficiency arm still reverses the ranked core list to pin to the slowest cluster. With capacity
ordering rather than frequency ordering that reversal is now a genuine slowest-first order.

### `cpu_capacities` in the upload payload

New field: per-core `cpu_capacity`, normalised so 1024 is the strongest core in the system. The
dataset should carry the canonical signal rather than a frequency proxy for it - `max_freqs_mhz`
cannot separate an A55 from an A78 at a similar clock, which is exactly the distinction the thread
width rules turn on. Empty array on kernels that do not export it.

The Supabase table has a matching nullable `cpu_capacities jsonb` column; the six existing rows keep
`NULL`. `contribute-schema.sql` and `CONTRIBUTE-BACKEND.md` are updated to match.

## Why this matters for the dataset

The prefill inversion that motivated chat v3.5.0 was only visible *because* contributed rows came
from four different SoCs. Two phones could not have shown it. Once `cpu_capacities` is being
gathered, the same dataset can answer the question this release had to leave open: whether the
decode thread count - which is still the frequency-based rule, because that is the rule validated
against measured devices - should move to capacity as well.
