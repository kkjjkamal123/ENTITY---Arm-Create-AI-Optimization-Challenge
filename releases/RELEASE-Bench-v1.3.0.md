# ENTITY Bench v1.3.0 - 2026-07-22

**APK:** `ENTITY-Bench-v1.3.0-release.apk` (release)

**The device card now says which of ENTITY's optimizations are live on the phone in your hand.**
The app already claimed a list of levers in its documentation; nothing on screen told you which of
them this particular CPU actually gets. A chip grid on the home screen now lists every lever and
lights only the ones that are real here, so the difference between "ENTITY supports this" and
"your phone runs this" is visible before a benchmark is started.

## Added

- **Optimization indicator on the DUT card** (`Optimizations.kt`, `DeviceInfo.cpuFlags()`,
  `Ui.optChip()`, `BenchHomeActivity.renderOptimizations()`). Every lever ENTITY ships gets a
  chip; a chip is filled (solid inversion) only when the lever is live on this device, and a dim
  dashed outline otherwise. It uses the same two-color vocabulary as the existing KleidiAI model
  badge rather than a colored glow, so it stays inside the strict mono theme.
- **ISA detection from `/proc/cpuinfo`** - the `Features` line is parsed for `dotprod`, `i8mm`,
  `sve`, `sve2`, `sme` and `fp16`. The honesty gate is that a flag present in `/proc/cpuinfo` is a
  flag the loaded ggml variant uses, because the build ships `GGML_CPU_ALL_VARIANTS` and ggml
  dlopens the strongest variant the CPU supports.
- Unit cover for the flag parser in `DeviceInfoTest`.

## Notes

- The i8mm chip deliberately does **not** claim a bespoke Q4_K kernel. It claims only that the
  loaded variant runs `MATMUL_INT8` for Q4_0 and Q8_0, which is what is actually true.
- Kotlin-only change: the native `.cxx` cache is reused and the measurement core is untouched, so
  v1.2.1 and v1.3.0 exports stay comparable.

## Upgrade notes

- versionCode 5 -> 6, same signing key: `adb install -r` upgrades without uninstalling.
