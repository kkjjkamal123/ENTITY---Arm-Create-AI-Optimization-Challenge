# ENTITY Bench v2.2.1 - the contribute screen actually contributes

**Status: released.** versionCode 13. Unit tests pass (39).

**Upgrading from 2.2.0 is clean.** Same signing certificate
(`5033b539d09d24f6e77c49e541c99a706b42210883d95e637bc1bb6031cafe0f`), so `adb install -r` works and
saved benchmark history survives. The certificate change described in
[v2.2.0](RELEASE-Bench-v2.2.0.md) still applies to anyone coming from 2.1.1 or earlier.

## What this release is

The bench half of a full-repo review that produced fifteen findings across both Android apps, the
iOS app and the native layer. Six landed here, and two of them were breaking things a user would
actually hit.

## The one that mattered most

**The Contribute screen refused to send.** `post()` gated on `ResultUploader.enabled()`, which
requires the global **Settings → Contribute results** toggle. But the Contribute screen exists
precisely for the other kind of consent: you open a picker, choose specific saved results, and tap
Send. Anyone who had never flipped that unrelated Settings switch got a generic failure immediately
after making an explicit choice to share - which is the one moment the app most owed them a working
button.

Consent now belongs to the caller, because the two callers have different consent. `post()` checks
only that the build has an endpoint. The automatic path - the upload that fires on its own when a
benchmark finishes, and the offline retry queue - keeps the Settings gate and now checks it
explicitly. Nothing is queued while contribution is off either: a result the user did not agree to
share must not sit on disk waiting for the day they enable the toggle for something else.

This is the fix with the widest blast radius, because the contributed dataset is what the
[device leaderboard](https://kkjjkamal123.github.io/ENTITY-WEB/leaderboard/) is made of.

## The one that quietly corrupted data

**The charging flag was a single reading taken before the first pass.** `ResultUploader` turns it
into `power_valid` for the public dataset, and `PowerMath.watts()` strips the sign of the measured
current on every sample. So a charger plugged or unplugged a minute into a multi-minute run never
reached the stored flag, while every subsequent watts reading was contaminated by charger current.
A clean run could be marked invalid, or - worse - a contaminated run uploaded as clean, and neither
is recoverable from the stored result afterwards.

Charging is now polled every 150 ms on the same clock as power, and the flag is **sticky**: a run is
contaminated if it was charging for any part of its duration, not if it happened to be charging when
someone last looked.

## Storage

**A finished benchmark could crash the app instead of saving.** `save()`'s `writeText` had no
`try`/`catch`, unlike every other I/O method in that file, even though the file's own header promises
every completed benchmark is written the moment it finishes. A full disk after a ten-minute
sustained run propagated an uncaught `IOException` out of the coroutine and took the process down -
losing the result and explaining nothing. The result file is now documented as the one thing worth
throwing over, the index update is best-effort, and `RunActivity` catches and reports rather than
dying.

**A delete could silently drop a just-finished run from history.** `delete()` does a
read-filter-rewrite of `index.jsonl` and `save()` appends to it, with nothing between them. Finish a
benchmark at the moment someone deletes an old entry and the rewrite lands without the new line -
the JSON result still on disk, unreferenced and invisible. Both now serialise behind one lock, reads
included, so a listing can never observe a half-rewritten index.

**A double tap could corrupt a model download.** Same fix as chat 3.7.1: one lock per catalog entry,
with the second caller waiting and re-checking the finished file rather than appending into the same
`.part`.

## Shared with chat

The GGUF header-bounds fixes and the unknown-free-memory catalog fix are in this build too - both
apps carry deliberately identical copies of `GgufMetadataReaderImpl.kt` and `ModelCatalog.kt`, and
the fixes were mirrored rather than reimplemented. Full detail in
[chat v3.7.1](RELEASE-v3.7.1.md).

## Tests

39 unit tests. `GgufHeaderBoundsTest` is new here as well, and `ModelCatalogTest` gained the
unknown-memory case.

## Files

`apk/ENTITY-Bench-v2.2.1-release.apk`

| | |
|---|---|
| SHA-256 | `5eaf3db5ae6908626ea3e9bef6d08f587ac181d1109edc796516bc8e1deb9a9d` |
| Size | 10,346,718 bytes (9.9 MB) |
| Certificate SHA-256 | `5033b539d09d24f6e77c49e541c99a706b42210883d95e637bc1bb6031cafe0f` |
| ABI / min SDK | arm64-v8a, Android 13+ |
