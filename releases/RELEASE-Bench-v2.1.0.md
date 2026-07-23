# ENTITY Bench v2.1.0 - the `adpf` arm

**Status: built and release-signed** as `apk/ENTITY-Bench-v2.1.0-release.apk` (versionCode 10,
same cert as prior releases). Unit tests pass. **Not yet run on a device.**

Chat v3.6.0 added an Android performance hint session - the one route an unprivileged app has into
the kernel's scheduler and cpufreq machinery. This release exists so that route is **measured
rather than assumed**, which is the same rule every other claim in this project had to satisfy.

## The `adpf` arm

Auto's thread count, affinity **off**, and the platform told the deadline for each decode step.

Unpinned on purpose: ADPF is the *alternative* to pinning, not an addition to it. A hard affinity
mask and a deadline hint are two different answers to the same question, and stacking them would
measure neither. So the comparisons that mean something are:

| against | isolates |
|---|---|
| `adpf` vs `threads_only` | same width, neither pinned - what the hint alone earns |
| `adpf` vs `optimized` | same width - a deadline hint versus a hard core mask |

The arm is off by default in the native layer, so **every existing arm measures exactly what it
measured before** and results stay comparable with v2.0.0 and earlier.

## Also fixed

The same `PowerMath` voltage-unit bug as chat v3.6.0 - `EXTRA_VOLTAGE` reported in volts rather
than millivolts made the microamp/milliamp heuristic fall through, under-reporting power by 1e6 on
an OPPO CPH2737 (Dimensity 8300). Any device with that quirk was producing meaningless watts and
tok/W. 7 regression tests.

This is the second time a contributed device has exposed something two development phones could
not - after the prefill thread width in v2.0.0. Both were invisible locally and obvious in the
dataset.

## What to run

A three-arm or four-arm ablation now produces a fifth row on devices where the hint session opens.
The interesting devices are the prime-core flagships, where `n_gen` sits on the `N_THREADS_MIN`
floor and pinning already showed the widest spread: a Tensor G5 gained 29.3% from pinning but lost
3.2% of its tokens per watt, which is exactly the trade a deadline hint is supposed to avoid
having to make.
