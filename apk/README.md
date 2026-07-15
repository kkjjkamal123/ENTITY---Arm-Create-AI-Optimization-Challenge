# Prebuilt APKs

One prebuilt debug or release-signed APK per version, ready to install on any arm64-v8a Android 13+ device.

| File | Version | Build | Size |
|---|---|---|---|
| `ENTITY-v10-sustained-thermal-20260715-release.apk` | **2.2.0** (current) | release-signed | 10.3 MB |
| `ENTITY-v9-kleidiai-quant-20260714-release.apk` | 2.1.0 | release-signed | |
| `ENTITY-v9-kleidiai-quant-20260714-debug.apk` | 2.1.0 | debug | |
| `ENTITY-v8-universal-arm-20260712-1240-release.apk` | 2.0.0 | release-signed | 9.8 MB |
| `ENTITY-v8-universal-arm-20260712-1240-debug.apk` | 2.0.0 | debug |  |
| `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` | 1.7.0 | release-signed | 7.0 MB |
| `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` | 1.7.0 | debug | 40.3 MB |
| `ENTITY-v6-chats-uipolish-20260710-2213.apk` | 1.6.0 | debug | |
| `ENTITY-v6-chats-Stripped.apk` | (size experiment) | debug | |
| `ENTITY-v5-ui-emptystate-20260704-1610.apk` | 1.5.0 | debug | |
| `ENTITY-v4-icon-chips-20260704-1259.apk` | 1.4.0 | debug | |
| `ENTITY-v3-benchmark-20260703-2118.apk` | 1.3.0 | debug | |
| `ENTITY-v2-modelinfo-progress-20260703-2048.apk` | 1.2.0 | debug | |
| `ENTITY-v1-runtime-graph-settings-20260703-1521.apk` | 1.1.0 | debug | |
| `ENTITY-optimized-single-variant-20260702-2335.apk` | 1.0.0 | debug | |

## Start here

**Recommended**: `ENTITY-v8-universal-arm-20260712-1240-release.apk` is the current release (v2.0.0), properly release-signed, and 9.8 MB. It ships 7 Arm CPU backend variants with automatic runtime selection for universal Arm support.

## Installation

All APKs are **arm64-v8a only** and require Android 13+. Install with:

```bash
adb install -r <filename>.apk
```

Then launch the app and import a GGUF model via the folder icon (Import from device).

## Build signatures

- **v1.7.0 onward** ship a properly release-signed APK (`CN=ENTITY`), along with a debug build for testing.
- **All earlier versions** (v1.6.0 and before) are debug-signed only; these remain suitable for offline use and competitive evaluation, but lack Play Store release eligibility.
