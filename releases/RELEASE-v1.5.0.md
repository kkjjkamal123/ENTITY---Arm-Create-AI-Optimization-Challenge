# ENTITY v1.5.0 — 2026-07-04

**APK:** `ENTITY-v5-ui-emptystate-20260704-1610.apk`

A UI-polish release focused on first impressions. Until now, opening ENTITY with no model loaded left
you staring at an empty black screen with only an input hint — functional, but it read as unfinished.
This version gives that screen a purpose: a centered ENTITY "E" mark, wordmark, and a "Fully offline ·
on-device AI" tagline that establishes what the app is before you've done anything, then gets out of the
way the instant a conversation begins. The metrics row was also reworked from a raw monospace font to
clean sans-serif so the numbers read as a designed stat line rather than terminal output. **Nothing about
inference changes** — models, chat, settings, and benchmarks all carry over unchanged, so this is a
drop-in update.

## Added
- **Branded empty state** — the launch / new-chat screen now shows the ENTITY "E" mark, wordmark, and
  "Fully offline · on-device AI" tagline, so a modelless screen reads as designed rather than blank. It
  hides automatically the moment a chat starts and returns on **New chat**.

## Changed
- **Stat row is sans-serif** — dropped the monospace font that made the metrics line look raw.
