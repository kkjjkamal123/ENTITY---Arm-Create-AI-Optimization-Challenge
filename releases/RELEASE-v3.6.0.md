# ENTITY v3.6.0 - deadline hints, and a power bug that made watts meaningless

**Status: built and release-signed** as `apk/ENTITY-v22-adpf-power-fix-20260723-release.apk`
(versionCode 19, same cert as every release since v3.0.0, so `adb install -r` upgrades in place).
Unit tests pass including the release variant. **ADPF has not yet run on a device** - see
*Verification*.

## The kernel question, answered honestly

An unprivileged Android app cannot do kernel optimization. No cpufreq governor, no scheduler
policy, no `/proc/sys`, no kernel module, no realtime priority class. All of it needs root, and a
rooted app would be unshippable and its benchmarks would stop representing a normal phone - which
is the thing that makes this project's numbers worth anything.

There is exactly one sanctioned route into those subsystems, and v3.6.0 takes it.

`sched_setaffinity` says **where** work runs. It cannot say **how fast it needs to be**, so the
kernel still picks a frequency by reacting to load after the fact, and a hard mask also stops the
platform migrating work when the phone heats up. That is why Android's own guidance is to avoid
manual affinity.

A [performance hint session](https://developer.android.com/games/optimize/adpf) says **when** the
work must finish. The framework hands that to the same scheduler and cpufreq machinery the vendor
already tuned, so it can raise clocks, place threads, and back off under thermal pressure - per
device, with no heuristic of ours to be wrong on silicon nobody here owns.

That last point is the real argument. The contributed dataset showed pinning ranging from -8.5% to
+29.3% on decode and slightly *negative* on tokens per watt in the median. A deadline hint moves
that decision to the device, which is where it belongs.

## What changed

### ADPF hint session

Opened at `prepare()` over the decode thread, closed on teardown. Each decode step's real duration
is reported, and the target is retargeted when the observed rate drifts more than 2x from it.

The core APIs are `__INTRODUCED_IN(33)` - exactly this app's minSdk - so they link directly with no
runtime guard. `APerformanceHint_getManager()` returns null on a device whose vendor did not
implement the HAL, and every call site tolerates that, so the feature is silently inactive rather
than fatal.

**Stated limitation:** a session boosts the threads registered in it. ggml's worker threads are
spawned inside the thread pool with no TID we can enumerate, so only the thread that runs
`llama_decode` is registered. Whether that is enough is a measurement, not an assumption - which
is why ENTITY Bench v2.1.0 carries an `adpf` arm and this release claims no speedup.

### Power measurement fix

An OPPO CPH2737 (Dimensity 8300) reported **2.7 microwatts** of decode and **11 million tokens per
watt**. The cause was the voltage, not the current.

`EXTRA_VOLTAGE` is documented in millivolts; that device reports whole **volts**. `PowerMath`
decides between microamp and milliamp current readings by asking which product is a physically
possible wattage - so with a 1000x-too-small voltage, *both* candidates fell below the plausible
floor, the heuristic gave up, and it returned the documented-unit branch. Two independent 1000x
errors compounding into the 1e6 observed.

`normalizeVoltageMv()` resolves the voltage unit first: `<100` volts, `>100,000` microvolts, else
millivolts. The three candidate ranges are three orders of magnitude apart, so magnitude alone
identifies the unit unambiguously.

**If a power reading ever looks wrong again, check the voltage unit before the current unit.** The
current heuristic is only as good as the voltage handed to it, and it fails silently.

## Verification

| Claim | How |
|---|---|
| Voltage unit resolution | 7 JVM unit tests per app, incl. the CPH2737 regression and a check that honest microamp devices are unaffected |
| Rest of the app still passes | full release-variant `testReleaseUnitTest` |
| ADPF actually linked into the shipped APK | `nm -D` on `libai-chat.so` shows `APerformanceHint_getManager/createSession/reportActualWorkDuration/updateTargetWorkDuration/closeSession` |
| ADPF is inert where unsupported | by construction - null manager, null session, guarded call sites |
| **A hint session opening on real hardware** | **not yet observed** |
| **Any ADPF speed or energy effect** | **not measured, and not claimed** |

## Data note

Contributed rows 9-14 (OPPO CPH2737) were produced by a build carrying the voltage bug. Their
decode and prompt throughput are unaffected and remain usable; their watts are not. Rows 11 and 12
wrongly carried `power_valid = true` and have been corrected to `false`. See
[`../benchmarks/CONTRIBUTED-DATA.md`](../benchmarks/CONTRIBUTED-DATA.md).
