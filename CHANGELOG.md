# Changelog

All notable changes to **ENTITY** — an offline, on-device LLM chat app for Arm phones, tuned for a
MediaTek Dimensity 7300 (4× Cortex-A78 + 4× Cortex-A55, 6 GB) on a CMF Phone 1. Format follows
[Keep a Changelog](https://keepachangelog.com); versions follow [Semantic Versioning](https://semver.org).

Each release maps to a shipped APK in `apk/` (see the **Artifacts** table at the bottom).
From v1.7.0 onward, both a debug-signed and release-signed APK are published per release, arm64-v8a only — install with `adb install -r <file>.apk`. **Positioning:** the app to
beat is **Arm's own AI Chat** (`com.arm.aichat`); ENTITY adds device-specific big.LITTLE tuning and a
tokens-per-watt efficiency axis AI Chat doesn't measure.

## [Unreleased]

### Added

- **Three-arm benchmark ablation.** The Benchmark screen now runs a third configuration between
  naïve and Auto: **threads-only** — Auto's derived thread count with core affinity switched off
  (no `sched_setaffinity`, no pinned thread pool, placement left to the scheduler). It is the
  in-app equivalent of an upstream llama.cpp `-t N` run.
- `pinCores` flag through `InferenceEngine.applyConfig` → JNI `configure()` → `g_pin_cores` in
  `ai_chat.cpp`. Defaults to true, so every shipped path is unchanged; only the ablation arm turns
  it off. `unpin_all_cores()` clears any affinity mask inherited from the previous arm.
- Decode attribution under the results table and in the copied text: how much of the naïve → Auto
  gain the thread count earns, and how much pinning adds on top.
- CSV export records the per-arm affinity policy (`affinity_naive`, `affinity_threads_only`,
  `affinity_optimized`) and the three-arm order; `threads_only` joins `naive`/`optimized` as a
  config key. `device-result-template.csv` gains the matching `threads_only_*` columns.

### Why

Naïve (8 threads, all cores) and Auto (4 threads, pinned) differ in **two** variables at once, so
the published +121% / +117% decode figures are the gain of the shipped configuration over the
out-of-the-box default — they do not say whether core pinning or the thread count earned it.
Dropping to four threads alone already stops the little cores from gating decode. The middle arm
holds the thread count fixed and removes only the pinning, so the two effects separate. The
existing two-arm tables are unchanged and relabelled as such; no threads-only number is estimated.
See [BENCHMARKS.md](benchmarks/BENCHMARKS.md#pending-the-three-arm-attribution).

## [2.0.0] — 2026-07-12

The headline: **universal Arm support via runtime CPU backend dispatch**. ENTITY previously shipped
a single CPU backend compiled for one known SoC. v2.0.0 ships **7 Arm CPU backend variants** (armv8.0,
armv8.2×2, armv8.6, armv9.0, armv9.2×2, each with KleidiAI kernels) and dynamically loads the best
one the phone's CPU supports at startup — so the app now runs on essentially any Arm Android phone
without crashing on older cores or missing faster kernels on new ones. A new **first-run "Optimize
for your device" dialog** detects the loaded backend's ISA features (e.g. i8mm, dotprod, SVE) and
suggests Auto mode. The big-core affinity optimization is unchanged and was already SoC-agnostic.

A **power measurement bug** was fixed: many OEM kernels report `BATTERY_PROPERTY_CURRENT_NOW` in
milliamps instead of the documented microamps, causing 1000× underreporting on affected devices; a
new `PowerMath` helper uses physical plausibility instead of trusting the unit. This fix is confirmed
on hardware via cross-device validation.

**Cross-vendor measurement:** the core optimization was independently validated on a second device
(Qualcomm Snapdragon 6 Gen 4) using the same model and protocol as the reference device. Reference
device (MediaTek Dimensity 7300): **8.0 → 17.7 tok/s (+121%), 1.7 → 4.2 tok/W (2.5×)**. Second
device (Snapdragon 6 Gen 4): **6.0 → 13.1 tok/s (+117%), 1.8 → 3.8 tok/W (2.1×)**. The core
optimization reproduces across vendors without vendor-specific code.

The in-app benchmark now measures ENTITY's **shipped Auto configuration** (split thread pools for
prompt/decode) instead of an explicit 4-thread test, so the result accurately reflects what users get.
UI strings are now SoC-neutral and report detected core counts and ISA features at runtime instead of
hardcoding Cortex-A78 names. Eight new unit tests cover device detection and power-unit resolution.

### Added
- **Runtime CPU backend dispatch** — 7 Arm variants with automatic selection at startup via
  `ggml_backend_load_all_from_path` + scoring. No UI toggle, no config — just works.
- **First-run "Optimize for your device" dialog** — detects perf/efficiency-core counts, ISA
  features (i8mm, dotprod, SVE2, etc.) from the loaded backend, suggests Auto mode. "Not now" to
  dismiss; can be re-run from Settings.
- **SoC-neutral model-info string** — reports actually-detected core counts and ISA features, not
  hardcoded Cortex-A78 names; works on MediaTek, Qualcomm, Samsung, etc.
- **`PowerMath` helper** — resolves `BATTERY_PROPERTY_CURRENT_NOW` unit (microamps vs milliamps) by
  physical plausibility instead of trusting the documented unit; fixes 1000× underreporting on
  affected OEM kernels (Qualcomm especially).
- **Unit tests** — `app/src/test/java/com/example/llama/DeviceOptimizerTest.kt`: 8 JUnit4 tests
  covering backend feature detection, core ranking, and device optimization; plus 6 tests for
  `PowerMathTest.kt` covering unit resolution and boundary cases.

### Changed
- **In-app benchmark measures the shipped configuration** — now runs Auto mode with split thread
  pools (generation on big cores, prompt processing on all cores) instead of an explicit 4-thread
  config. The naive control (8 threads, all cores) unchanged; protocol (PP 512 / TG 128) unchanged.

### Fixed
- **Power readout was 1000× wrong on some devices** — OEM milliamp/microamp confusion now resolved
  via plausibility, not trust-the-unit. Affects only power/efficiency display, not tok/s measurements.
- **Power graph received garbage on unsupported devices** — `getIntProperty` sentinel value was
  feeding bad data; now guarded.

---

## [1.7.0] — 2026-07-12

A polishing release focused on battery life and reliability. The headline is **Efficiency mode**, a
toggle in Settings that trades speed for power: when on, inference is capped at 2 threads (vs. the
usual 4 pinned to the Cortex-A78s) and thermal throttle delays are doubled. The implementation
includes a new `ThermalGuard` object that maps Android's `PowerManager.currentThermalStatus` to a
periodic delay evaluated every eight generated tokens — status NONE/LIGHT → 0 ms, MODERATE → 6 ms, SEVERE+ → 12 ms, doubled under
Efficiency mode — and the thermal status is cached so the token loop never pays a binder call. The
live power readout is now a 5-sample moving average instead of a single instantaneous reading, which
removes the jitter and makes the on-screen figures more meaningful. The release build is now signed
with a real release keystore (instead of the debug config), so app installers and store tools can
verify the APK signature. Five new unit tests cover the thermal guard's status-to-delay mapping,
efficiency-mode doubling, and monotonicity across all statuses.

### Added
- **Efficiency mode** (Settings toggle) — caps inference at 2 threads, doubles thermal throttle delays.
- **Periodic thermal guard** — `ThermalGuard` maps `PowerManager.currentThermalStatus` to
  a delay evaluated every eight generated tokens (0/6/12 ms); cached so the token loop incurs no binder calls.
- **Windowed power sampling** — live watts readout is a 5-sample moving average, eliminating jitter.
- **Proper release signing** — release APK signed with a dedicated release keystore (separate from
  debug), with gitignored `keystore.properties` file. Debug signing used as fallback if keystore
  is absent, so contributors are never blocked.
- **Unit tests** — `app/src/test/java/com/example/llama/ThermalGuardTest.kt`: 5 JUnit4 tests
  covering status→delay mapping, efficiency-mode doubling, and monotonicity.

### Changed
- **Release build signing** — shifted from debug keystore to release keystore (CN=ENTITY, etc.),
  credentials read from gitignored `keystore.properties`.

---

## [1.6.0] — 2026-07-10

The biggest release since 1.0.0, on three fronts at once: the runtime got faster where it was
weakest, the app became a real daily tool, and the UI got the polish pass it deserved. On the
inference side, prompt processing now runs on all eight cores through a dedicated thread pool while
generation stays pinned to the four Cortex-A78s — prompt-heavy turns start noticeably sooner, and
decode keeps its bandwidth-bound sweet spot. A conversation that outgrows the context window now
trims its oldest turns (system prompt preserved) and keeps going instead of failing, and two latent
position-accounting bugs in that path were fixed along the way. On the app side, chats finally
persist: every conversation is stored in a local SQLite database, survives process death, and can be
switched, renamed, or deleted from a new Conversations menu — with a new engine API that re-primes
the KV cache from stored history so a restored chat continues seamlessly. Rotation no longer loses
state (a proper ViewModel owns the chat now), generation survives backgrounding, the system prompt
is editable in Settings, and assistant answers render markdown with long-press Copy/Regenerate. The
UI was refreshed end to end — softer bubbles, a typing indicator, subtle entry animations behind a
new Animations toggle that also honors Android's Remove-animations accessibility setting. The in-app
benchmark grew up too: multi-run with median ± stddev, thermal-gated cooldowns between passes, TTFT,
and CSV export. And the release build finally acts like one: R8-minified with stripped native
symbols, the APK drops from ~100 MB to ~7 MB.

### Added
- **Chat persistence + multiple conversations** — chats stored in a local SQLite DB (`chats.db`),
  auto-titled from the first message; a Conversations menu lists them (tap to switch, long-press to
  rename/delete); the most recent conversation is restored on launch, and partial answers are saved
  if you hit Stop.
- **`primeHistory` engine API** — rebuilds the KV cache from a stored conversation without
  generating, so a restored or switched chat continues exactly where it left off.
- **System-prompt editor** (Settings) — multiline editor with reset-to-default; used on every
  load/new-chat/re-prime.
- **Markdown rendering** — bold, italic, inline code, fenced code blocks, bullets, and headings in
  assistant messages; hand-rolled renderer, parsed once per completed message and cached.
- **Long-press message actions** — Copy any message; Regenerate the last answer.
- **Animations toggle** (Settings, default on) — one switch disables all app animations instantly,
  and Android's "Remove animations" accessibility setting is honored automatically.
- **Multi-run benchmark** — 1/3/5 runs per configuration with median ± stddev, thermal-gated
  cooldown between passes (live temperature status), derived TTFT, and CSV export via SAF.
- **Release build** — R8-minified, resource-shrunk, native symbols stripped: **~7 MB APK** vs the
  ~100 MB debug build; signed for sideloading.

### Changed
- **Prompt processing uses all 8 cores** — a dedicated ggml thread pool spans the whole SoC during
  prompt evaluation while generation keeps its own pool pinned to the 4 big cores
  (`llama_set_n_threads` per phase, thread-pool functions resolved at runtime for the
  `GGML_BACKEND_DL` build, with a clean fallback). Faster time-to-first-token on long prompts;
  decode unchanged by design.
- **UI polish pass** — refined bubbles (softer radii, subtle strokes), typing-dots indicator while
  generating, message entry fade-rise, ripple + press-scale on the send button, refreshed input bar;
  both themes, no new libraries, no bitmaps, hardware-accelerated view-property animations only.
- **Rotation/theme changes keep everything** — chat and in-flight generation now live in a
  ViewModel; the engine is only torn down when the app actually exits, and generation continues
  while backgrounded.

### Fixed
- **Context-full no longer breaks a long conversation** — the KV window trims its oldest turns
  (system prompt preserved) with correct position accounting; previously a mid-generation or
  mid-prompt shift could desync positions and corrupt the remaining budget.
- **JNI teardown hardening** — engine error paths free their batch/context, sampler updates are
  create-then-swap, unload is double-free safe, and destroying the engine from an error state no
  longer crashes.
- **Per-token allocation removed** on the partial-UTF-8 streaming path.

### File comparison (1.5.0 → 1.6.0)

**New files**

| File | Purpose |
|---|---|
| `app/src/main/java/com/example/llama/ChatDb.kt` | SQLite persistence (conversations + messages) |
| `app/src/main/java/com/example/llama/ChatViewModel.kt` | Chat/generation state ownership, priming, regenerate |
| `app/src/main/java/com/example/llama/Anim.kt` | Central animation gate (user toggle + system setting) |
| `app/src/main/java/com/example/llama/TypingDotsView.kt` | Typing indicator (single reused Paint/animator) |
| `app/src/main/java/com/example/llama/Markdown.kt` | Lightweight markdown → Spanned renderer |
| `app/src/main/res/animator/press_scale.xml` | Send-button press feedback |

**Modified files**

| File | Change |
|---|---|
| `lib/src/main/cpp/ai_chat.cpp` | PP/TG thread pools, context-trim fixes, `primeHistoryNative`, JNI hardening |
| `lib/.../InferenceEngine.kt`, `InferenceEngineImpl.kt` | `ChatTurn` + `primeHistory` API |
| `app/.../MainActivity.kt` | ViewModel observation, Conversations dialog, animation gate, clipboard |
| `app/.../MessageAdapter.kt` | Markdown cache, typing dots, entry animation, long-press menu |
| `app/.../BenchmarkActivity.kt` | Multi-run median±stddev, cooldown, TTFT, CSV export |
| `app/.../Settings.kt`, `SettingsActivity.kt` | System-prompt + Animations settings |
| `app/build.gradle.kts` | 1.6.0, release signing, symbol stripping, `ndkVersion` |
| `gradle/libs.versions.toml` | lifecycle-runtime/viewmodel-ktx 2.9.4 |
| layouts / drawables / colors / strings | Visual refresh (both themes), new rows and strings |

---

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
KleidiAI` backend — arm64 only — instead of compiling unused generic CPU variants, which
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
- **Adaptive context** — sizes the context window from model size and free RAM: a 3B-class model gets a
  4096-token window above 2.2 GB free and a 2048-token window at or below it.
- **Professional chat UI** — in-app model picker, smooth token streaming, toggleable stats (tokens,
  tok/s, TTFT, temperature, **power draw in watts**, free memory), and light/dark/system themes.
- **Black-background white-"E" app icon.**
- **Fixes over early builds** — 3B "error reading file" (KV OOM + a bad on-device copy), model
  switching, and robotic "*beep*boop*" replies (via a strict system prompt).

---

## Artifacts

| Version | APK (in `apk/`) |
|---|---|
| 2.0.0 | `ENTITY-v8-universal-arm-20260712-1240-debug.apk` (debug, ~49 MB) · `ENTITY-v8-universal-arm-20260712-1240-release.apk` (release-signed, ~9.8 MB) |
| 1.7.0 | `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` (debug, ~40 MB) · `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` (release-signed, ~7 MB) |
| 1.6.0 | `ENTITY-v6-chats-uipolish-20260710-2213.apk` (debug) |
| 1.5.0 | `ENTITY-v5-ui-emptystate-20260704-1610.apk` (debug) |
| 1.4.0 | `ENTITY-v4-icon-chips-20260704-1259.apk` (debug) |
| 1.3.0 | `ENTITY-v3-benchmark-20260703-2118.apk` (debug) |
| 1.2.0 | `ENTITY-v2-modelinfo-progress-20260703-2048.apk` (debug) |
| 1.1.0 | `ENTITY-v1-runtime-graph-settings-20260703-1521.apk` (debug) |
| 1.0.0 | `ENTITY-optimized-single-variant-20260702-2335.apk` (debug) |
