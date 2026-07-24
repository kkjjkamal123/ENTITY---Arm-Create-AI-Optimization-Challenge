# ENTITY v3.6.2 - the assistant had no idea it was ENTITY

**Status: built and release-signed** as `apk/ENTITY-v24-entity-identity-prompt-20260724-release.apk`
(versionCode 21, same cert as every release since v3.0.0, so `adb install -r` upgrades in place).
Unit tests pass.

## The gap

The default system prompt said the assistant was "a helpful AI assistant running fully offline on
the user's phone." True, but thin: nothing in it told a small model what app it was running inside,
or what it structurally cannot do. Left to its own defaults, a base-tuned checkpoint will answer
identity questions as whatever persona it was trained on, and will guess at capabilities - web
search, image generation - it does not have, rather than say so.

Neither gap is hypothetical here. Every model in the catalog is text-only, and `ai_chat.cpp` has no
image pipeline and makes no network call anywhere in the inference path. A prompt that does not say
so leaves the model free to hallucinate otherwise.

## What changed

`Settings.kt`'s `DEF_SYSTEM_PROMPT` now states plainly: what ENTITY is (offline, built for Arm
phones), what it cannot do (browse the web, look up real-time information, generate images), and to
say so instead of guessing when asked. The existing behavioral constraint (no roleplay, no narrated
actions, no robotic sound effects - added earlier after exactly that failure mode) is unchanged.

This is a default only. Existing users who have not edited their system prompt keep the old one
until they reset it; only a fresh install or an explicit reset picks up the new default. ENTITY
Bench has no chat surface, so this does not apply there.

## Verification

| Claim | How |
|---|---|
| Compiles clean, existing behaviour unaffected | `assembleRelease` + `testReleaseUnitTest`, both green |
| Cert matches prior releases (`adb install -r` upgrades in place) | `apksigner verify --print-certs`, matches `f34cd27c...` |
| **Answers identity/capability questions correctly in real chat** | **not yet observed - no device this session** |
