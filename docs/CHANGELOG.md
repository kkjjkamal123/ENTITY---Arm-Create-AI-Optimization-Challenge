# Changelog

All notable changes to **ENTITY** — an offline, on-device LLM chat app for Arm phones, tuned for a
MediaTek Dimensity 7300 (4× Cortex-A78 + 4× Cortex-A55, 6 GB) on a CMF Phone 1. Format follows
[Keep a Changelog](https://keepachangelog.com); versions follow [Semantic Versioning](https://semver.org).

Each release maps to a backed-up APK in `backups/apk/` (see the **Artifacts** table at the bottom).
Debug builds, arm64-v8a only — install with `adb install -r <file>.apk`. **Positioning:** the app to
beat is **Arm's own AI Chat** (`com.arm.aichat`); ENTITY adds device-specific big.LITTLE tuning and a
tokens-per-watt efficiency axis AI Chat doesn't measure.

## [1.5.0] — 2026-07-04

A UI-polish release focused on first impressions. Until now, opening ENTITY with no model loaded left
you staring at an empty black screen with only an input hint — functional, but it read as unfinished.
This version gives that screen a purpose: a centered ENTITY "E" mark, wordmark, and a "Fully offline ·
on-device AI" tagline that establishes what the app is before you've done anything, then gets out of the
way the instant a conversation begins. The metrics row was also reworked from a raw monospace font to
clean sans-serif so the numbers read as a designed stat line rather than terminal output. **Nothing about
inference changes** — models, chat, settings, and benchmarks all carry over unchanged, so this is a
drop-in update.

### Added
- **Branded empty state** — the launch / new-chat screen now shows the ENTITY "E" mark, wordmark, and
  "Fully offline · on-device AI" tagline, so a modelless screen reads as designed rather than blank. It
  hides automatically the moment a chat starts and returns on **New chat**.

### Changed
- **Stat row is sans-serif** — dropped the monospace font that made the metrics line look raw.

### File comparison (1.4.0 → 1.5.0)

**New files**

| File | Purpose |
|---|---|
| `app/src/main/res/drawable-nodpi/entity_mark.png` | Tintable "E" glyph for the empty state |

**Modified files**

| File | Change |
|---|---|
| `app/src/main/res/layout/activity_main.xml` | Empty-state overlay in a `FrameLayout`; stat row → sans-serif |
| `app/src/main/java/com/example/llama/MainActivity.kt` | `emptyState` view + `updateEmptyState()` wired to message add/clear |
| `app/src/main/res/values/strings.xml` | `empty_tagline` |

---

## [1.4.0] — 2026-07-04

A personalization release that also hardened the benchmark path. The headline feature is a theme-aware
app-icon switcher: ENTITY ships with two logos — a black-background and a white-background "E" — and this
version lets you choose between them, or set **Auto** so the launcher icon matches your phone's light or
dark theme automatically. It's implemented with launcher activity-aliases rather than a hack, with a
guard that guarantees exactly one icon is ever enabled so the app can never vanish from your home screen.
Alongside that, the title bar gained always-on temperature and free-RAM chips for at-a-glance device state
during a run. This release also absorbed three fixes an adversarial code review turned up on the new
benchmark: a power-sampler race that could crash a run, and a core-affinity leak that could quietly leave
the chat running on the slow efficiency cores after benchmarking — plus a same-session fix for an
icon-switch crash and an over-shrunk icon glyph.

### Added
- **Theme-aware app-icon switcher** (Settings → App icon) — *Auto* (black-bg icon in dark theme,
  white-bg in light), *Black background*, or *White background*, implemented with launcher
  activity-aliases so exactly one icon is ever enabled.
- **Header chips** — always-on temperature and free-RAM pills below the title, refreshed live during
  generation but throttled to ~1×/sec so they don't cost decode speed.

### Fixed
- **Benchmark could crash mid-run** — the power sampler was cancelled without waiting, so reading the
  samples list could race the sampler thread (`ConcurrentModificationException`). Now `cancelAndJoin`
  before reading.
- **Chat could get stuck on the slow cores after a benchmark** — `benchModel` restored the context
  tracker but not the CPU-affinity globals it overwrites, so a benchmark could leave the chat pinned
  across all 8 cores (including the A55s) until reload. Now saved/restored around the benchmark.
- **Icon switch crashed the app** — the launcher aliases live in the `com.example.llama` namespace but
  the code built the component name from the `com.entity.chat` applicationId, so enabling the alias
  threw and (because the choice was saved first) crash-looped on every launch. Fixed the namespace and
  wrapped the switch so a failure can never take down launch.
- **App icon glyph was tiny** — the source logos have wide transparent margins; the icons are now
  cropped to the "E" so it fills the tile instead of floating small inside it.

### File comparison (1.3.0 → 1.4.0)

**New files**

| File | Purpose |
|---|---|
| `app/src/main/java/com/example/llama/IconStyle.kt` | Enables/disables the launcher aliases safely |
| `app/src/main/res/drawable/bg_chip.xml` | Rounded pill background for the header chips |
| `app/src/main/res/mipmap-*/ic_entity_black.png`, `ic_entity_white.png` | Per-density launcher icons (both themes) |

**Modified files**

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | `MainBlack` / `MainWhite` activity-aliases replace the direct launcher |
| `app/src/main/java/com/example/llama/MainActivity.kt` | `IconStyle.apply` on launch; header chips + throttled metric read |
| `app/src/main/java/com/example/llama/SettingsActivity.kt` | App-icon chooser dialog |
| `app/src/main/java/com/example/llama/BenchmarkActivity.kt` | `cancelAndJoin` on the power sampler |
| `lib/src/main/cpp/ai_chat.cpp` | Save/restore `g_fast_cpus`/`g_fast_count` in `benchModel` |
| `app/src/main/res/layout/activity_main.xml` | `header_chips` row |
| `app/src/main/res/layout/activity_settings.xml` | `row_icon` |
| `app/src/main/res/values/strings.xml` | Icon strings |

---

## [1.3.0] — 2026-07-03

This release turns ENTITY's core claim into something you can measure inside the app. The whole point of
the project is that pinning inference to the Cortex-A78 big cores beats a naïve all-cores run on this
chip — but that was only provable from the command line. The new in-app benchmark runs the exact same
workload (PP 512 / TG 128) twice back-to-back on the loaded model, once naïve across all eight cores and
once with ENTITY's four-big-core optimization, then shows both side by side with the speed delta. It uses
the same prompt-processing / token-generation framing that Arm AI Chat and PocketPal report, so the
numbers are directly comparable — but it adds the axis they don't measure at all: **power draw in watts
and tokens-per-watt**, reframing on-device AI as an efficiency problem and not just a speed one. A latent
correctness bug was fixed in the process, where the benchmark's throwaway context could corrupt the live
chat's context bounds.

### Added
- **In-app benchmark** (⋮ → Benchmark) — runs the same PP 512 / TG 128 test twice on the loaded model,
  **naïve** (8 threads, all cores) vs **optimized** (4 threads pinned to the Cortex-A78 big cluster),
  and reports prompt/decode tok/s, **power (W)**, and **tokens-per-watt**, with a copyable result. Same
  PP/TG framing Arm AI Chat and PocketPal use, plus the energy axis they omit.

### Fixed
- **Benchmark corrupted the chat's context bounds** — `benchModel` builds its own small context, which
  overwrote the global context-size tracker; it now saves/restores it, so running a benchmark no longer
  affects the loaded chat.

### File comparison (1.2.0 → 1.3.0)

**New files**

| File | Purpose |
|---|---|
| `app/src/main/java/com/example/llama/BenchmarkActivity.kt` | Two-pass benchmark, power sampling, result table |
| `app/src/main/res/layout/activity_benchmark.xml` | Benchmark screen |

**Modified files**

| File | Change |
|---|---|
| `lib/src/main/cpp/ai_chat.cpp` | `benchModel` saves/restores `g_n_ctx` |
| `app/src/main/java/com/example/llama/MainActivity.kt` | `openBenchmark`; store `KEY_ACTIVE_CTX` on load |
| `app/src/main/java/com/example/llama/Settings.kt` | `KEY_ACTIVE_CTX` |
| `app/src/main/AndroidManifest.xml` | Register `BenchmarkActivity` |
| `app/src/main/res/menu/main_menu.xml` | `action_benchmark` |
| `app/src/main/res/values/strings.xml` | Benchmark strings |

---

## [1.2.0] — 2026-07-03

A release about getting a model into the app on *any* phone, not just the developer's. The previous
builds expected you to copy a `.gguf` file into the app's `Android/data/…` folder, which works over adb
but is silently blocked for normal file managers on modern Android's scoped storage — so on a second
phone, loading a model was effectively impossible. This version replaces that with a proper in-app
**Import from device** flow built on the Storage Access Framework: you pick the file from anywhere in
your storage and ENTITY copies it in for you, with a real progress percentage while it does. Imported
models now keep their actual filename instead of an `imported-<timestamp>` placeholder, and a new model
info card reads the GGUF header to show exactly what you loaded — parameters, quantization, architecture,
trained vs running context, and the CPU compute path. A dead-end empty-state dialog that offered no way
to import was also fixed.

### Added
- **Loading progress bar** — a real percentage while importing a picked file, then an indeterminate bar
  while the engine loads, so large models don't look frozen.
- **Model info card** (⋮ → Model info) — reads the GGUF header and shows parameters, quantization,
  architecture, trained vs running context, layers, embedding, vocab, and the CPU compute path.

### Fixed
- **Model loading failed on other phones** — the app told users to copy the file into
  `Android/data/…`, which scoped storage blocks on modern Android. Models are now added via an in-app
  **Import from device** picker (Storage Access Framework) that copies the file in for you.
- **Imported models kept a junk name** — they now keep their real filename instead of
  `imported-<timestamp>`.
- **Empty picker was a dead end** — with no models, the dialog showed a message and no way to act; it
  now shows a real **Import from device…** button (an Android dialog can't show a message *and* a list).

### File comparison (1.1.0 → 1.2.0)

**Modified files**

| File | Change |
|---|---|
| `app/src/main/java/com/example/llama/MainActivity.kt` | SAF import with real names; progress bar; model-info card; picker empty-state |
| `app/src/main/res/layout/activity_main.xml` | `load_container` progress bar |
| `app/src/main/res/menu/main_menu.xml` | `action_model_info` |
| `app/src/main/res/values/strings.xml` | `menu_model_info` |

---

## [1.1.0] — 2026-07-03

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

### Added
- **Live metrics graph** — six independently toggleable series: tokens, tok/s, TTFT, temperature,
  power, and free memory.
- **Settings screen with a master "Auto (optimized)" toggle** — plus manual tuning of temperature,
  top-k, top-p, max tokens, context size, and thread count when Auto is off.
- **Stop and New chat** — interrupt a generation, or clear the conversation to ask something fresh.
- **About / Optimizations page** — documents what the app does under the hood.

### Changed
- **JNI hardened during streaming** — `@FastNative` kept only on short calls, lighter main-thread work
  per token, and cancellation-safe cleanup so leaving mid-generation can't wedge the UI.

### Fixed
- **Max-token over-generation** — corrected the position/count math so generation stops at the
  requested length instead of over-running and truncating.

### File comparison (1.0.0 → 1.1.0)

**New files**

| File | Purpose |
|---|---|
| `app/src/main/java/com/example/llama/MetricsGraphView.kt` | Custom multi-series graph view |
| `app/src/main/java/com/example/llama/Settings.kt` | Shared config keys (single source of truth) |
| `app/src/main/java/com/example/llama/SettingsActivity.kt` | Settings screen + Auto toggle |
| `app/src/main/java/com/example/llama/InfoActivity.kt` | About / Optimizations page |
| `app/src/main/res/layout/activity_settings.xml`, `activity_info.xml` | Screens for the above |
| `app/src/main/res/drawable/ic_stop_24.xml`, `ic_new_chat_24.xml` | Stop / New-chat icons |

**Modified files**

| File | Change |
|---|---|
| `app/src/main/java/com/example/llama/MainActivity.kt` | Graph sampling, Stop/New-chat, adaptive context, thermal guard, stat toggles |
| `lib/src/main/java/com/arm/aichat/InferenceEngine.kt` | `applyConfig` / `applySampler` / `newConversation` |
| `lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` | Impl of the above; `@FastNative` and cancellation fixes |
| `lib/src/main/cpp/ai_chat.cpp` | `configure` / `setSampler` JNI + runtime config; max-token fix |
| `app/src/main/res/menu/main_menu.xml` | New-chat / graph / settings / about + stat toggles |
| `app/src/main/AndroidManifest.xml` | Register `SettingsActivity` and `InfoActivity` |

---

## [1.0.0] — 2026-07-02

The initial release, and the one that set ENTITY apart from a generic offline chat app. Rather than run a
desktop-style build on a phone, this version was tuned specifically to the CMF Phone 1's Dimensity 7300.
Inference is pinned to the four Cortex-A78 big cores so the memory-bandwidth-bound decode stays off the
slow A55 efficiency cluster, and the native library is compiled as a single `armv8.2-a + dotprod +
KleidiAI` backend — arm64 only — instead of the seven generic CPU variants a stock build ships, which
makes the app smaller, quicker to launch, and lighter on RAM. An adaptive-context scheme sizes the KV
window from the model and available memory, which is what lets a 3B model load and generate within roughly
2 GB of free RAM. On top of that runtime sits a proper chat UI with an in-app model picker, smooth token
streaming, a live watts readout, and light/dark/system themes — plus fixes for the early-build problems
that made larger models fail to load and made the model reply with robotic sound effects.

**Key components**
- **Big-core affinity** — inference pinned to the Cortex-A78 performance cluster via
  `sched_setaffinity`, with cores chosen by live max frequency (keeps work off the slow A55 cores).
- **Device-tuned CPU backend** — a single `armv8.2-a + dotprod + KleidiAI` build instead of the seven
  generic CPU variants; **arm64-v8a only** (x86 removed) for a smaller, faster, lighter app.
- **Adaptive context** — sizes the context window from model size and free RAM, so a 3B model fits and
  runs within ~2 GB free (4096 KV).
- **Professional chat UI** — in-app model picker, smooth token streaming, toggleable stats (tokens,
  tok/s, TTFT, temperature, **power draw in watts**, free memory), and light/dark/system themes.
- **Black-background white-"E" app icon.**
- **Fixes over early builds** — 3B "error reading file" (KV OOM + a bad on-device copy), model
  switching, and robotic "*beep*boop*" replies (via a strict system prompt).

---

## Artifacts

| Version | APK (in `backups/apk/`) |
|---|---|
| 1.5.0 | `ENTITY-v6-ui-emptystate-20260704-1610.apk` |
| 1.4.0 | `ENTITY-v5-icon-chips-20260704-1259.apk` |
| 1.3.0 | `ENTITY-v4-benchmark-20260703-2118.apk` |
| 1.2.0 | `ENTITY-v3-modelinfo-progress-20260703-2048.apk` |
| 1.1.0 | `ENTITY-v2-runtime-graph-settings-20260703-1521.apk` |
| 1.0.0 | `ENTITY-optimized-single-variant-20260702-2335.apk` |
