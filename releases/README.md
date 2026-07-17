# Release notes

One file per version, each a self-contained, copy-paste-ready **GitHub Release** body: version, date,
the matching APK filename, an intro paragraph, and Added/Changed/Fixed bullets for that version only.

| File | Version | APK |
|---|---|---|
| [`RELEASE-Bench-v1.1.0.md`](RELEASE-Bench-v1.1.0.md) | Bench 1.1.0 | `ENTITY-Bench-v1.1.0-release.apk` (release) |
| [`RELEASE-v2.4.0.md`](RELEASE-v2.4.0.md) | 2.4.0 | `ENTITY-v12-kv-session-adaptive-threads-20260717-release.apk` (release) |
| [`RELEASE-v2.3.0.md`](RELEASE-v2.3.0.md) | 2.3.0 | `ENTITY-v11-ui-polish-20260715-release.apk` (release) |
| [`RELEASE-v2.2.0.md`](RELEASE-v2.2.0.md) | 2.2.0 | `ENTITY-v10-sustained-thermal-20260715-release.apk` (release) |
| [`RELEASE-v2.1.0.md`](RELEASE-v2.1.0.md) | 2.1.0 | `ENTITY-v9-kleidiai-quant-20260714-debug.apk` (debug) + `ENTITY-v9-kleidiai-quant-20260714-release.apk` (release) |
| [`RELEASE-v2.0.0.md`](RELEASE-v2.0.0.md) | 2.0.0 | `ENTITY-v8-universal-arm-20260712-1240-debug.apk` (debug) + `ENTITY-v8-universal-arm-20260712-1240-release.apk` (release) |
| [`RELEASE-v1.7.0.md`](RELEASE-v1.7.0.md) | 1.7.0 | `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` (debug) + `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` (release) |
| [`RELEASE-v1.6.0.md`](RELEASE-v1.6.0.md) | 1.6.0 | `ENTITY-v6-chats-uipolish-20260710-2213.apk` (debug) |
| [`RELEASE-v1.5.0.md`](RELEASE-v1.5.0.md) | 1.5.0 | `ENTITY-v5-ui-emptystate-20260704-1610.apk` |
| [`RELEASE-v1.4.0.md`](RELEASE-v1.4.0.md) | 1.4.0 | `ENTITY-v4-icon-chips-20260704-1259.apk` |
| [`RELEASE-v1.3.0.md`](RELEASE-v1.3.0.md) | 1.3.0 | `ENTITY-v3-benchmark-20260703-2118.apk` |
| [`RELEASE-v1.2.0.md`](RELEASE-v1.2.0.md) | 1.2.0 | `ENTITY-v2-modelinfo-progress-20260703-2048.apk` |
| [`RELEASE-v1.1.0.md`](RELEASE-v1.1.0.md) | 1.1.0 | `ENTITY-v1-runtime-graph-settings-20260703-1521.apk` |
| [`RELEASE-v1.0.0.md`](RELEASE-v1.0.0.md) | 1.0.0 | `ENTITY-optimized-single-variant-20260702-2335.apk` |

## How these map to GitHub Releases

Each file here is meant to become one [GitHub Release](https://docs.github.com/en/repositories/releasing-projects-on-github):

1. Tag the commit: `git tag v1.0.0 && git push origin v1.0.0` (repeat per version, in order).
2. On GitHub: **Releases → Draft a new release → choose the tag**.
3. Paste the matching `RELEASE-vX.Y.Z.md` body into the release description.
4. Attach the matching APK from [`../apk/`](../apk/) as a release asset (GitHub Releases accept files
   up to 2 GB, well above the 100 MB per-file limit that applies to a normal `git push`).

These notes are derived from — and must stay consistent with — [`../CHANGELOG.md`](../CHANGELOG.md),
which remains the canonical, Keep-a-Changelog-formatted history. The `CHANGELOG.md` entries additionally
include a "File comparison" section per version (useful for developers reading the repo history); that
detail is intentionally left out of these release notes to keep them clean for an end-user-facing
GitHub Release.
