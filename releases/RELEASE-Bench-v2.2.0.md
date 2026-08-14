# ENTITY Bench v2.2.0 - the catalog, brought level with chat

**Status: released** as `apk/ENTITY-Bench-v2.2.0-release.apk`. versionCode 12. Unit tests pass. Superseded by [v2.2.1](RELEASE-Bench-v2.2.1.md).

**The signing key changed.** Same cause as chat v3.7.0 - the keystore used for prior releases was
lost and cannot be reissued ([`docs/JOURNEY.md`](../docs/JOURNEY.md) §11). Installing fresh is
unaffected; upgrading over v2.1.1 fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and needs an
uninstall first, **which deletes saved benchmark history.** Open each result you want to keep and
export it to CSV before uninstalling.

## The gap

Bench and chat ship the same curated GGUF catalog on purpose - a benchmark tool is useless if it
cannot offer the same models the app it measures does. That copy had drifted. Bench still carried
**8 entries against chat's 19**, no vendor or role on any of them, and a fit rule written against
installed RAM.

Worse for a tool whose whole job is measurement: bench's `:lib` had no tensor census, so it could
only assert KleidiAI eligibility from the filename - the exact shortcut chat had already been fixed
not to take.

## What changed

**The catalog is one file again.** `ModelCatalog.kt` is now byte-identical between the two apps
except for a single line: bench calls `DeviceInfo.cpuFeatures`, chat calls
`DeviceOptimizer.cpuFeatures`, because the two apps named the same helper differently before the
file was shared. The two are separate Gradle builds with separate `:lib` modules, so there is no
shared module to hold it - identical files that can be diffed beat a fork.

That brings bench 19 entries across seven vendors, 0.36B to 7B, each tagged with vendor and role,
and shown on the catalog card.

**Measured KleidiAI coverage.** `GgmlType`, `TensorCensus` and the tensor-table parsing in
`GgufMetadataReaderImpl` are ported from chat's `:lib`, along with their tests. Bench can now report
the fraction of a file's weights that actually reach Arm's kernels rather than trusting
`general.file_type` - which matters here, because a file named `Q4_0` is routinely only 76% Q4_0.

**Fit judged against free memory.** Same rule as chat v3.7.0: sizing uses the memory the system
reports available rather than installed RAM, since a 6 GB phone with background apps resident often
has under 2 GB to give. Thresholds re-derived rather than reused - fractions calibrated against
total would reject models that genuinely run.

## Not ported: the device probe

Chat v3.7.0 estimates decode and prefill before a download. Bench deliberately does not get it. This
app exists to *measure* those numbers on real hardware with a three-arm ablation; shipping an
estimate beside a measurement would invite the two to be confused, and the estimate is the weaker
of the two by construction.

## Build fixes

A clean checkout of the bench module did not build on a stock toolchain, for two reasons that
between them produced no useful error message:

- The CMake version was pinned to 3.31.6, above what the Android SDK bundles.
- `jvmToolchain(17)` was nested inside the `android` block in `:lib` and absent from `:app`, so the
  Kotlin compiler inherited whatever JDK ran the Gradle daemon. On a modern one the frontend dies
  parsing the version string itself - `IllegalArgumentException: 26.0.2`, naming no file and
  pointing at nothing.

Both are fixed, matching the chat module.
