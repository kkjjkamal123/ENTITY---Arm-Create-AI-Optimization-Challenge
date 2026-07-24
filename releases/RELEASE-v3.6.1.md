# ENTITY v3.6.1 - repetition penalty was off

**Status: built and release-signed** as `apk/ENTITY-v23-repeat-penalty-20260724-release.apk`
(versionCode 20, same cert as every release since v3.0.0, so `adb install -r` upgrades in place).
Unit tests pass including the release variant. **Not yet tried on a device this session** - see
*Verification*.

## The bug

`new_sampler()` set `temp`, `top_k` and `top_p` from the user's config and left everything else at
`common_params_sampling`'s own defaults. That struct's own default for `penalty_repeat` is `1.0` -
disabled. Combined with ENTITY's `temp = 0.3` (deliberately conservative, for grounded answers over
creative ones), the sampler ran close to greedy decoding: whichever token was already most likely
stayed most likely, with nothing to break a self-reinforcing loop once one started.

That is the textbook setup behind two complaints that came back together: a chat that repeats
itself and a chat that reads bland are not opposite symptoms calling for opposite fixes - they are
the same collapse (Holtzman et al., *The Curious Case of Neural Text Degeneration*, 2019). Fixing
it does not require raising temperature, which would trade directly against the factual grounding
`temp = 0.3` was chosen for.

## What changed

`penalty_repeat = 1.1` - the standard mitigation value - fixed internally in `ai_chat.cpp`. Not yet
a user-facing setting. `top_k`, `top_p`, `temp`, and `min_p` (already on at the library default of
`0.05`) are untouched, so if this measurably helps, the improvement is attributable to this one
lever alone.

## Verification

| Claim | How |
|---|---|
| Compiles clean (native + Kotlin) | `assembleRelease` green |
| Existing behaviour unaffected | full release-variant `testReleaseUnitTest` |
| **Reduces looping or blandness in real chat** | **not yet observed - no device available this session** |

## What this does not fix

Four symptoms were reported: looping, blandness, ignoring instructions/format, and losing
coherence deep into a long chat. This release addresses the first two directly, and the third only
if it was the same collapse read differently (a model stuck looping also reads as ignoring the
requested format). The fourth is a different subsystem - `shift_context()` discards the oldest half
of the conversation in one shot on context overflow, already flagged as a TODO in the code - and is
untouched here. Treat this as lever one, not a resolved diagnosis, until it is tried on-device.
