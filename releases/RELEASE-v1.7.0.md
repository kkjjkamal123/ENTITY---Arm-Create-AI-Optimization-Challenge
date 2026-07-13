# ENTITY v1.7.0 — 2026-07-12

**APK:** `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` (debug) · `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` (release, ~7 MB)

A polishing release focused on battery life and reliability. The headline is **Efficiency mode**, a
toggle in Settings that trades speed for power: when on, inference is capped at 2 threads and
thermal throttle delays are doubled. The implementation includes a new `ThermalGuard` object that
maps Android's `PowerManager.currentThermalStatus` to a per-token delay — status NONE/LIGHT → 0 ms,
MODERATE → 6 ms, SEVERE+ → 12 ms, doubled in Efficiency mode — and the thermal status is cached
so the token loop never pays a binder call. The live power readout is now a 5-sample moving average
instead of instantaneous, eliminating jitter. The release APK is now signed with a real release
keystore instead of the debug config, so app installers and stores can verify the signature. Five
new unit tests cover the thermal guard's status-to-delay mapping, efficiency-mode doubling, and
monotonicity across all statuses.

## Added
- **Efficiency mode** (Settings toggle) — caps inference at 2 threads, doubles thermal throttle delays.
- **Per-token thermal guard** — `ThermalGuard` maps `PowerManager.currentThermalStatus` to per-token
  delays (0/6/12 ms); cached so token loop incurs no binder calls.
- **Windowed power sampling** — live watts readout is a 5-sample moving average, eliminating jitter.
- **Proper release signing** — release APK signed with a dedicated release keystore (separate from
  debug), with gitignored `keystore.properties` file. Debug signing used as fallback if keystore
  is absent, so contributors are never blocked.
- **Unit tests** — `app/src/test/java/com/example/llama/ThermalGuardTest.kt`: 5 JUnit4 tests
  covering status→delay mapping, efficiency-mode doubling, and monotonicity.

## Changed
- **Release build signing** — shifted from debug keystore to release keystore (CN=ENTITY, OU=Mobile, O=ENTITY),
  credentials read from gitignored `keystore.properties`.

## Verification
- `./gradlew :app:assembleDebug :app:assembleRelease` → **BUILD SUCCESSFUL**.
- `./gradlew :app:testDebugUnitTest` → **5 tests, 0 failures**.
- Release APK verified with `apksigner` → signed by `CN=ENTITY, OU=Mobile, O=ENTITY, L=Unknown, ST=Unknown, C=IN`.
