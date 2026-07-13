# ENTITY v1.6.0 — 2026-07-10

**APK:** `ENTITY-v6-chats-uipolish-20260710-2213.apk` (debug)

The biggest release since 1.0.0. Chats now **persist**: every conversation is stored locally, survives
process death, and can be switched, renamed, or deleted from a new Conversations menu — a restored chat
re-primes the engine's KV cache from stored history and continues exactly where it left off. Prompt
processing now runs across all eight cores through a dedicated thread pool while generation stays pinned
to the four Cortex-A78 big cores, so long prompts start answering sooner without touching decode. The UI
got a full polish pass — softer bubbles, markdown rendering, a typing indicator, and subtle animations
behind a new toggle that also honors Android's Remove-animations accessibility setting. The benchmark
grew median ± stddev multi-runs, thermal-gated cooldowns, TTFT, and CSV export. And a proper release
build (R8 + stripped native symbols) drops the APK from ~100 MB to ~7 MB.

## Added
- **Chat persistence + multiple conversations** — local SQLite storage, auto-titles, switch/rename/delete,
  partial answers saved on Stop, last conversation restored on launch.
- **`primeHistory` engine API** — rebuilds KV state from a stored conversation without generating.
- **System-prompt editor** in Settings (multiline, reset-to-default).
- **Markdown rendering** in assistant messages (bold, italic, code, fences, bullets, headings), cached.
- **Long-press Copy / Regenerate** on messages.
- **Animations toggle** — disables all app animations instantly; system Remove-animations honored automatically.
- **Multi-run benchmark** — 1/3/5 runs, median ± stddev, thermal-gated cooldown, TTFT, CSV export.
- **Release build** — R8-minified, symbols stripped: ~7 MB APK, signed for sideloading.

## Changed
- **Prompt processing uses all 8 cores** via a dedicated thread pool; generation keeps its 4-big-core pool.
- **Rotation/backgrounding keep everything** — chat and generation live in a ViewModel now.
- **UI polish** — refined bubbles, typing dots, entry animations, ripple send button, both themes, zero new libraries.

## Fixed
- Conversations that outgrow the context window now trim oldest turns (system prompt preserved) with
  correct position accounting instead of desyncing.
- JNI teardown hardening — no crashes from error-state cleanup, sampler swap, or double unload.
