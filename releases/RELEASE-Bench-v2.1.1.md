# ENTITY Bench v2.1.1 - sampler parity, and a Settings fix

**Status: built and release-signed** as `apk/ENTITY-Bench-v2.1.1-release.apk` (versionCode 11, same
cert as prior releases). Unit tests pass. UI change not yet seen on a device this session.

## Sampler parity with chat v3.6.1

Chat v3.6.1 enabled `penalty_repeat = 1.1` in `ai_chat.cpp`, disabled since launch - the library's
own default for that field is `1.0`. Bench carries its own copy of the same file, so the same
change is mirrored here for one reason: bench exists to measure what chat actually ships, and a
diverged sampler would make its numbers unrepresentative of the real app. Decode throughput stays
comparable with v2.1.0 and earlier - the penalty is a cheap per-token logit adjustment over the
last 64 tokens, not a change to the forward pass - but any future analysis of contributed rows
should treat v2.1.1+ as carrying this change and earlier rows as not.

## Contribute section, Settings: a toggle that did not look like one

Reported directly: the on/off control for sharing benchmark results (`row_contribute`) was styled
identically to the two navigation buttons directly below it - "Choose results to share" and "Show
exactly what gets sent" - three outline buttons in a row with no visual cue that only one of them
was a persistent switch. A background-fill inversion was the only signal it had been turned on, and
that signal looked the same as any other button's outline-to-solid press state.

Also found in the process: `row_contribute_open`, a second "Choose results to share" button
(solid-style, stacked directly under the working outline one) with no click listener wired to it at
all - a dead duplicate that did nothing when tapped.

Fixed: the toggle is now a checkbox row - an 18dp square (`Ui.check`, mirroring the pattern the
chat app already uses for its own settings toggles) next to a title and an explicit description
("Off by default - nothing is sent until this is on"), visually distinct from the outline action
buttons below it. The dead duplicate button is deleted.

## Verification

| Claim | How |
|---|---|
| Compiles clean, IDs resolve (XML <-> Kotlin) | `assembleRelease` + `testReleaseUnitTest`, both green |
| Sampler output unaffected in shape (same arms, same CSV keys) | by construction - no arm/JSON schema change |
| **Checkbox actually renders and reads clearly on a phone** | **not yet observed - no device this session** |
