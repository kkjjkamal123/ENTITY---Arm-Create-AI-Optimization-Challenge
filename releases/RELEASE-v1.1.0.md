# ENTITY v1.1.0 — 2026-07-03

**APK:** `ENTITY-v1-runtime-graph-settings-20260703-1521.apk`

This release turns ENTITY from a chat app into a tunable, observable runtime. The idea was to stop
treating the phone as a black box and expose both the levers and the readouts. On the observability side,
a live metrics graph plots six signals — tokens, tokens/sec, time-to-first-token, temperature, power, and
free memory — each independently toggleable so you can watch exactly what you care about. On the control
side, a settings screen adds a master **Auto (optimized)** switch that lets ENTITY pick context size and
threads for the device, with a full manual layer (temperature, top-k, top-p, max tokens, context, threads)
underneath for when you want to drive. Everyday controls arrived too: a **Stop** button to interrupt a
generation and a **New chat** button to start clean, plus an About / Optimizations page documenting what
the app does under the hood. Under the surface, the JNI layer was hardened for streaming so leaving
mid-generation can no longer wedge the UI.

## Added
- **Live metrics graph** — six independently toggleable series: tokens, tok/s, TTFT, temperature,
  power, and free memory.
- **Settings screen with a master "Auto (optimized)" toggle** — plus manual tuning of temperature,
  top-k, top-p, max tokens, context size, and thread count when Auto is off.
- **Stop and New chat** — interrupt a generation, or clear the conversation to ask something fresh.
- **About / Optimizations page** — documents what the app does under the hood.

## Changed
- **JNI hardened during streaming** — `@FastNative` kept only on short calls, lighter main-thread work
  per token, and cancellation-safe cleanup so leaving mid-generation can't wedge the UI.

## Fixed
- **Max-token over-generation** — corrected the position/count math so generation stops at the
  requested length instead of over-running and truncating.
