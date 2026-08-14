# ENTITY v3.7.1 - what a full-repo review found

**Status: released.** versionCode 23. Unit tests pass (85, up from 72).

**Upgrading from 3.7.0 is clean.** Same signing certificate
(`5033b539d09d24f6e77c49e541c99a706b42210883d95e637bc1bb6031cafe0f`), so `adb install -r` works and
conversations survive. The certificate change described in
[v3.7.0](RELEASE-v3.7.0.md) still applies to anyone coming from 3.6.2 or earlier.

## What this release is

No features. A full-repo review over all ~131k lines - both Android apps, the iOS app, the native
C++/JNI layer, the benchmark tooling - produced fifteen findings. This is those fixes, plus fifteen
new tests so the ones with a testable seam stay fixed.

Ten of the fifteen were in this app. They are listed most-severe first, and each is described by
what it would actually have done to someone, because that is the only ranking that means anything.

## The native layer

**A heap overflow in `benchModel()`.** `g_batch` is allocated once with 512 slots and never resized.
The benchmark filled it with `pp` entries and, per generation step, `pl` entries - both
caller-supplied, neither bounded. `decode_tokens_in_batches` chunks by `BATCH_SIZE` for exactly this
reason; this path never did. The shipped UI passes `pp = 512`, which sits precisely at the limit, so
nothing was corrupt in practice - but this is a public JNI entry point and the next caller need not
be the shipped UI. It now rejects out-of-range arguments rather than clamping them: a benchmark that
silently measures a smaller workload than it was asked for reports a number for a configuration
nobody requested, which is worse than no number.

**A null dereference under memory pressure.** `GetStringUTFChars` allocates, so it can fail - and it
fails exactly when a multi-gigabyte model has just been loaded, returning `nullptr` with an
`OutOfMemoryError` already pending. Four call sites fed that straight into a `std::string`
constructor, which is undefined behaviour and in practice takes the process down at the one moment
the Kotlin side could have shown an error and carried on. A single `jni_copy_string` helper now
handles all four, and deliberately leaves the pending exception pending: it is a real OOM and Java
should see it.

**Token positions past the end of the KV cache.** `shift_context()` frees half of what lies between
the system prompt and the current position, so it frees *nothing* when the two are adjacent - which
is the state a system prompt sized close to `max_batch_size` leaves behind. Both callers ignored the
return value and decoded anyway, handing `llama_decode()` out-of-range positions. Both now re-check
after the shift; `decode_tokens_in_batches` fails and `generateNextToken` ends the turn, because
there is genuinely nowhere to put those tokens and a short answer beats a corrupt one.

## The GGUF parser

Every count and length in a GGUF header is a 64-bit field, and every loop here is driven by an `Int`.
The conversions between the two were bare `toInt()` calls, which is not a harmless narrowing:
**`2^32` truncates to `0`**. A corrupt header did not fail - it reported zero metadata pairs, or a
zero-element array, having consumed none of the bytes those items occupy, and everything downstream
then parsed from the wrong offset with no exception anywhere to explain it.

The worst of the three was the `skipValue` string branch. `skipFully`'s `while (remaining > 0)` loop
reads a negative length as "skip nothing", so a corrupted length on a skipped key - `tokenizer.chat_template`
is in the default skip set - left the stream pointer sitting on the template's own text, and every
subsequent key, value and the entire tensor-info table was parsed from the wrong offset. Silently.

All four narrowings now go through one `checkedCount`, which fails naming the field. New in this
release: `GgufHeaderBoundsTest` builds real corrupt headers and asserts each one throws, plus a
well-formed fixture proving a skipped key still advances by exactly its own length.

## Chat behaviour

**A stopped answer was indistinguishable from a finished one.** Tapping Stop cancelled the job, but
`onCompletion` still wrote the partial text to the database as an ordinary assistant turn. The
fragment then came back as context on the next prompt - and an assistant turn that stops
mid-sentence teaches the model that stopping mid-sentence is what an answer looks like.

Messages now carry a `truncated` flag (**database migration v2 → v3**, existing rows default to
`false`). The fragment is still saved and still rendered exactly as generated - it was on screen and
deleting it on reload would be its own bug - but the copy replayed to the model is annotated. Only
the model's view changes.

**A failed memory query read as a large phone.** `ActivityManager` returns 0 or a negative figure on
some devices, and `assess()` answered `OK` for every catalog entry, skipping the size check
entirely. `recommended()` then ranked on parameter count with nothing to stop it and offered the
largest 7.62B / 4.4 GB model to a device whose free memory nobody could read - precisely the case
most likely to end in an OOM kill on first load.

It now sizes against a conservative assumed 1.5 GB rather than special-casing, so every existing
rule applies unchanged and only the source of the number differs. The reason string says so on every
affected row. In practice the recommendation moves from a 4.4 GB download to a 1.07 GB one.

**Escaped characters vanished from rendered maths.** A backslash before a non-letter is an escape,
not a macro, and the fallback consumed both the backslash and the character while appending nothing.
`$\{1, 2, 3\}$` rendered as a set with no braces; `$5\ \text{kg}$` merged into "5kg". Five new tests
cover `\{ \} \% \_ \& \#`, the forced space, `\\`, and a trailing lone backslash.

**A download could be corrupted by a double tap.** Two overlapping `download()` calls for the same
entry both opened a `FileOutputStream` on the same `.part` file. Both read the same starting byte
count, both appended from there, and the interleaved result can still reach the expected length by
coincidence - length is the only completeness check - after which it is renamed to `.gguf` and handed
to the native loader. There is now one lock per catalog entry; a second caller waits and re-checks
the finished file, which is what someone who tapped twice actually wanted.

## Tests

85 unit tests, up from 72. The fifteen added cover the LaTeX escapes, the unknown-memory path, and
the GGUF header bounds. The parity fixture that holds the site's predictor to `DeviceProbe.kt` grew
an `unknown-free-memory` profile, so the recommender change is asserted in both languages.

## Not in this release

The review's iOS finding - a multi-select delete resolving offsets against an already-shrunk array,
deleting the wrong run - is fixed in source but ships whenever the iOS app is next built.

## Files

`apk/ENTITY-v26-review-fixes-20260814-release.apk`

| | |
|---|---|
| SHA-256 | `e97ec05fa29f1184db0f5c5fb3430e8db091fa4a80b6d7a62c2f3dc41d1243d8` |
| Size | 10,499,561 bytes (10.0 MB) |
| Certificate SHA-256 | `5033b539d09d24f6e77c49e541c99a706b42210883d95e637bc1bb6031cafe0f` |
| ABI / min SDK | arm64-v8a, Android 13+ |
