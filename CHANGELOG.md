# Changelog

All notable changes to **ENTITY** — an offline, on-device LLM chat app for Arm phones, tuned for a
MediaTek Dimensity 7300 (4× Cortex-A78 + 4× Cortex-A55, 6 GB) on a CMF Phone 1. Format follows
[Keep a Changelog](https://keepachangelog.com); versions follow [Semantic Versioning](https://semver.org).

Each release maps to a shipped APK in `apk/` (see the **Artifacts** table at the bottom).
From v1.7.0 onward a release-signed APK is published per release (debug builds through v2.1.0), arm64-v8a only — install with `adb install -r <file>.apk`. **Positioning:** the app to
beat is **Arm's own AI Chat** (`com.arm.aichat`); ENTITY adds device-specific big.LITTLE tuning and a
tokens-per-watt efficiency axis AI Chat doesn't measure.

## [3.6.2] - 2026-07-24

**The assistant had no idea it was ENTITY.** The default system prompt said only that it was "a
helpful AI assistant running fully offline" - true, but not enough to stop a small model from
answering identity questions as whatever base persona it was trained on, or from guessing at
capabilities (web search, image generation) it does not have. Neither is hypothetical: every model
in the catalog is text-only, and `ai_chat.cpp` has no image pipeline, no network call in the
inference path.

### Changed

- **Default system prompt** (`Settings.kt`). Now states plainly what ENTITY is (offline, Arm,
  on-device), what it structurally cannot do (browse, look up real-time information, generate
  images), and to say so instead of guessing when asked. Existing users who have not edited their
  system prompt keep the old default until they reset it - this only changes what a fresh install
  or a reset-to-default gets. Bench has no chat surface, so this is chat-only.

### File comparison (3.6.1 -> 3.6.2)

| File | Change |
|---|---|
| `Settings.kt` | `DEF_SYSTEM_PROMPT` rewritten with app identity and capability grounding. |

## [3.6.1] - 2026-07-24

**Repetition penalty was off.** `new_sampler()` only ever set `temp`, `top_k` and `top_p` from the
user's config; everything else - `min_p`, DRY, XTC, and critically `penalty_repeat` - rode
llama.cpp's own `common_params_sampling` defaults, and that struct's own default for
`penalty_repeat` is `1.0`, i.e. disabled. Paired with ENTITY's own `temp = 0.3` (deliberately low,
for grounded answers over creative ones), the sampler ran close to greedy decoding: whichever token
was already most likely stayed most likely, with nothing to break a loop once one started.

That is the textbook setup behind two complaints that came back together - a chat that repeats
itself and a chat that reads bland are not opposite symptoms calling for opposite fixes, they are
the same collapse (Holtzman et al., *The Curious Case of Neural Text Degeneration*, 2019). Fixing
it does not require raising temperature, which would trade directly against the factual grounding
`temp = 0.3` was chosen for.

### Fixed

- **Repetition penalty enabled** (`ai_chat.cpp`). `penalty_repeat = 1.1`, the standard mitigation
  value, fixed internally - not yet a user-facing setting. `top_k`, `top_p`, `temp`, and `min_p`
  (already active at the library default of `0.05`) are untouched, so if this measurably helps, the
  improvement is attributable to this one lever alone.

### Verification

| Claim | How |
|---|---|
| Compiles clean, native + Kotlin, both apps | `assembleRelease` + `testReleaseUnitTest`, both green |
| **Reduces looping or blandness in real chat** | **not yet observed - no device available this session** |

**This is lever one of a diagnosis, not a confirmed fix.** Four symptoms were reported: looping,
blandness, ignoring instructions/format, and losing coherence deep into a long chat. This release
addresses the first two directly, and the third only if it was the same collapse read differently
(a model stuck looping also reads as ignoring the requested format). The fourth is a different
subsystem - `shift_context()` discards the oldest half of the conversation in one shot on context
overflow, already flagged as a TODO in the code - and is untouched here. Treat all four as open
until this is tried on-device.

### File comparison (3.6.0 -> 3.6.1)

| File | Change |
|---|---|
| `ai_chat.cpp` | `g_penalty_repeat = 1.1f`; `new_sampler()` sets `sparams.penalty_repeat`. Mirrored identically in ENTITY Bench v2.1.1 - see its own release notes. |

## [3.6.0] - 2026-07-23

**Two things: the app now tells the kernel its deadline instead of only telling it which cores to
use, and a power-measurement bug that silently produced physically impossible numbers is fixed.**

An unprivileged Android app cannot touch kernel tunables - no cpufreq governor, no scheduler
policy, no `/proc/sys`, no realtime class. All of that needs root, and a rooted app would be both
unshippable and unrepresentative of a normal phone. But there is one sanctioned route into exactly
those subsystems, and ENTITY now uses it.

`sched_setaffinity` says *where* work runs. It cannot say *how fast it needs to be*, so the kernel
still picks a frequency by reacting to load after the fact - and a hard mask also stops the
platform migrating work when the phone heats up, which is why Android's own guidance is to avoid
manual affinity. A performance hint session says *when* the work must finish. The framework feeds
that to the same scheduler and cpufreq machinery the vendor already tuned, so it can raise clocks,
place threads, and back off under thermal pressure - per device, with no heuristic of ours.

This is deliberately shipped as something to **measure, not assume**: ENTITY Bench v2.1.0 carries
an `adpf` arm, and no claim is made about it until devices report back.

### Added

- **ADPF performance hint session** (`ai_chat.cpp`). Opens over the decode thread, reports each
  decode step's real duration, and retargets when the observed rate drifts more than 2x from the
  deadline. Core APIs are `__INTRODUCED_IN(33)`, which is this app's minSdk, so no runtime guard
  is needed; every call degrades to a no-op on a device whose vendor did not implement the HAL.
  Honest limitation: a session boosts the threads registered in it, and ggml's workers are spawned
  inside the pool with no TID to enumerate, so only the thread that runs `llama_decode` is
  registered.

### Fixed

- **Battery power was under-reported by 1,000,000x on some devices** (`PowerMath.kt`). An OPPO
  CPH2737 (Dimensity 8300) reported 2.7 **microwatts** of decode and 11 million tokens per watt.
  The cause was voltage, not current: `EXTRA_VOLTAGE` is documented in millivolts and that device
  reports whole **volts**. `PowerMath` chooses between microamp and milliamp current readings by
  asking which product is a physically possible wattage - so with a 1000x-too-small voltage *both*
  candidates fell under the plausible floor, the heuristic gave up, and it returned the
  documented-unit branch. Two independent 1000x errors compounding.
  `normalizeVoltageMv()` now resolves the voltage unit first: `<100` is volts, `>100,000` is
  microvolts, otherwise millivolts. The three ranges are three orders of magnitude apart, so
  magnitude alone identifies the unit. 7 regression tests.

### File comparison (3.5.0 -> 3.6.0)

| File | Change |
|---|---|
| `ai_chat.cpp` | `adpf_open/close/report/retarget`; session opened at `prepare()`, closed on teardown; per-token duration reporting; `configure()` takes an `adpf` flag. |
| `PowerMath.kt` | `normalizeVoltageMv()`; `watts()` resolves the voltage unit before the current unit. |
| `PowerMathTest.kt` | 7 tests: volt/millivolt/microvolt forms, the CPH2737 regression, and that honest microamp devices are unaffected. |
| `InferenceEngine.kt`, `InferenceEngineImpl.kt` | `applyConfig(..., adpf)`. |

## [3.5.0] - 2026-07-23

**Prompt processing was running on two threads on every flagship.** Contributed benchmarks made it
visible: a Dimensity 7300 prefills Llama-3.2-1B-Q4_0 at 139 tok/s while an SM8550 - far stronger
silicon, with i8mm - manages 111. Two causes compounded. `top_cluster_core_count()` counts cores
within 10% of the fastest, which is a proxy for "the performance cluster" that only holds on chips
with no prime core; every modern flagship puts its prime 17-20% above its own big cluster, so the
count collapsed to 1 and only `N_THREADS_MIN` pulled it back to 2. Prefill then inherited that
number, because `n_pp = n_gen`.

Decode was never the problem - it is memory-bandwidth-bound and two threads already saturates it
(an SM8550 decodes 23.8 tok/s on 2 threads and 6.72 on 8), so `n_gen` is deliberately **unchanged**.

Also new: core placement is now a user choice rather than an assumption. Across the contributed
dataset pinning ranges from -8.5% to +29.3% on decode and is slightly *negative* on tokens per watt
in the median - it buys speed and pays for it in power. Android's own guidance is that forcing
affinity stops the platform reacting to load and thermal throttling. So the app measures both and
lets the user pick.

### Added

- **LaTeX rendering in chat** (`Latex.kt`). Models emit LaTeX and it used to render as raw source.
  `$..$`, `$$..$$`, `\(..\)` and `\[..\]`, mapped to Unicode where Unicode suffices and to
  custom Canvas spans where it does not - stacked fractions with a real rule, radicals with a
  vinculum. Zero new dependencies, in the same hand-rolled spirit as `Markdown.kt`. Currency is not
  mistaken for math. 19 unit tests.
- **Core placement setting** (Settings -> Inference): *Auto* / *Perf cores* / *Scheduler*. The
  ablation benchmark already runs threads-only and optimized at the same thread count, differing
  only in affinity, so Settings reports which won on *this* phone and offers one-tap apply. The
  energy half of that verdict is suppressed for charging runs, whose watts are the charger's.
- **`cpu_capacity` core detection** (`ai_chat.cpp`). The kernel's normalised per-core capacity is
  the signal the scheduler itself uses; frequency cannot separate an A55 at 2.0 GHz from an A78 at
  2.5 GHz. Falls back to `cpuinfo_max_freq` where the kernel omits it.

### Fixed

- **Prefill thread width decoupled from decode** (`n_pp`). Prompt processing now runs on the
  performance cluster - every core strictly above the slowest frequency/capacity tier - while the
  decode thread count stays narrow. Verified against the four contributed topologies under both the
  capacity signal and the frequency fallback: `n_gen` 4/2/2/2 unchanged, `n_pp` now 4/6/5/4.
- **The pinned CPU set now covers the prefill pool.** `pin_to_fast_cores()` sets the calling
  thread's mask and ggml's workers inherit it lazily, so a set built for `n_gen` alone would have
  confined the wider batch pool to the decode cores.
- **The benchmark no longer overrides the user's placement choice.** Its restore path hardcoded
  `pinCores = true`, so finishing a run silently re-pinned someone who had chosen the scheduler.

### File comparison (3.4.1 -> 3.5.0)

| File | Change |
|---|---|
| `Latex.kt` | New. LaTeX -> Spanned: scanner, Unicode tables, `FractionSpan`, `RadicalSpan`. |
| `LatexTest.kt` | New. 19 tests over delimiters, currency, symbols, scripts, group parsing. |
| `Markdown.kt` | Math lifted out before the emphasis pass; `$$`/`\[` display blocks. |
| `ai_chat.cpp` | `cpu_weights()`, `perf_cluster_core_count()`, `prompt_thread_count()`; capacity-ranked affinity; `n_pp` decoupled at both attach sites. |
| `Settings.kt` | `KEY_PLACEMENT`, `PLACEMENT_*`, `pinCores()`. |
| `SettingsActivity.kt` | `buildPlacement()` - control, verdict, apply button. |
| `BenchHistory.kt` | Persists the pinned/unpinned arm pair; `pinSpeedPct`, `pinEnergyPct`, `powerValid`. |
| `BenchmarkActivity.kt` | Records the placement pair; restore paths honour the setting. |
| `MainActivity.kt` | Chat `applyConfig` passes the user's placement. |

## [3.4.1] - 2026-07-22

**Content was running underneath the system bars and hard against the edge of the screen.** Every
layout carried `android:fitsSystemWindows="true"` and it was inert: from targetSdk 35 Android draws
apps edge-to-edge and stops honouring that attribute on ordinary containers - it only ever worked
on inset-aware layouts such as `DrawerLayout`. ENTITY's screens are plain `LinearLayout`s, so they
got no inset padding at all.

### Fixed

- **Window-inset handling on every screen** (`Insets.kt`), derived from system bars *and* display
  cutout, so nothing sits under the status bar, navigation bar or camera cutout.
- **Insets add to each layout's declared padding** rather than replacing it, so screens keep their
  own gutters.
- **Padding goes on containers**, so lists still scroll under the bars without content resting there.
- **The chat input row takes the navigation-bar and IME insets directly**, so it rises with the
  keyboard.

### File comparison (3.4.0 -> 3.4.1)

| File | Change |
|---|---|
| `Insets.kt` | New. System-bar + cutout + optional IME padding helper. |
| `MainActivity.kt` | Insets on the chat column, drawer pane and input row. |
| `ModelsActivity.kt`, `SettingsActivity.kt`, `InfoActivity.kt`, `BenchmarkActivity.kt`, `BenchHistoryActivity.kt` | Root inset padding. |
| layouts | Inert `fitsSystemWindows` removed; `chat_column` / `input_row` ids added. |

## [3.4.0] - 2026-07-22

**MONO was too bright to use for long, and that was a measurable fault rather than a matter of
taste.** Every previous version painted pure `#FFFFFF` on pure `#000000` - a 21:1 contrast
ratio. On a black field the iris opens wider and white glyphs bleed into their own edges, an
effect called halation; it reads as glare within minutes and is worst for the roughly half of
people with some astigmatism. Material's dark-theme guidance is never to use black as the base
surface for exactly this reason. This release retunes the palette, keeps the monochrome
identity, and makes colour optional rather than absent.

### Changed

- **Pure black and pure white retired.** Dark is `#121212` base / `#1E1E1E` cards / `#E4E4E4`
  text - Material's dark-surface baseline and its 87% high-emphasis text level. Light is
  `#F1F0EC` paper / `#F9F8F5` cards / `#1F1F1D` ink. Body contrast 21:1 -> 15:1, still above
  WCAG AAA (7:1).
- **Cards sit lighter than the page in light theme**, following Carbon's alternating layering
  model rather than piling white on white - less emitted light at identical text contrast.
- **Borders are 1dp of a dedicated outline tone**, not 2dp at full text strength. A 2dp bright
  border around every card is a large amount of lit area, and area is what makes a UI glare.
- **Filled areas are dimmer than text** in dark theme (`mono_fill` < `mono_fg`), because a
  full-width bright slab contributes far more perceived glare than a line of type.
- **Secondary text has its own token** instead of being full-strength type set smaller.

### Added

- **Palette switch (Settings -> Theme): MONOCHROME or COLOUR.** Monochrome remains the default
  and is unchanged in character. Colour keeps identical layout, spacing and luminance and
  varies only hue: lightly tinted surfaces, one accent on the primary action, separate danger
  and success tones. Semantic tones are deliberately not the accent hue - sharing a colour
  between brand and error makes identity indistinguishable from warning. Every coloured state
  still carries text or shape saying the same thing, so meaning never depends on colour alone
  (WCAG 1.4.1).
- **Colour addressed by role, as theme attributes** (`res/values/attrs.xml`). Layouts,
  drawables and colour state lists reference `?attr/monoFg` and friends; raw colour resources
  now appear only inside the two palette themes. That indirection is what makes a runtime
  palette switch possible.

### Fixed

- **A model could not be reloaded after reopening the app.** `KEY_ACTIVE_MODEL` persists across
  restarts but the engine does not, so a freshly opened app showed the last model as LOADED on
  a disabled button with nothing loaded, and offered no way to load it. Loaded state is now
  reported by the chat screen from real engine state and passed to the Models screen. The same
  stale flag also wrongly blocked deleting that model.

### File comparison (3.3.0 -> 3.4.0)

| File | Change |
|---|---|
| `attrs.xml` | New. Ten role attributes for colour. |
| `colors.xml`, `values-night/colors.xml` | Retuned; semantic tokens added. |
| `colors_chroma.xml` (+ night) | New. The COLOUR palette. |
| `bools.xml` (+ night) | New. System-bar polarity, replacing a duplicated night theme. |
| `themes.xml` | Split into `Theme.Entity.Base` + `Theme.Entity` / `Theme.Entity.Chroma`. |
| `values-night/themes.xml` | Deleted - a redeclared style replaces rather than merges. |
| `Palette.kt` | New. Pref, theme selection, attribute resolution. |
| `SettingsActivity.kt` | Palette segmented control. |
| `MainActivity.kt`, `ModelsActivity.kt` | Real loaded-state via `EXTRA_LOADED`. |
| layouts, drawables, `res/color/` | `@color/mono_*` -> `?attr/mono*` (21 files). |

## [3.3.0] - 2026-07-22

**Models get a screen instead of a dialog.** v3.2.0 put the catalog in the model picker, and the
picker was an alert dialog with a list of strings: every entry became four or five lines of
wrapped prose, stacked with no separation between one model and the next. The information was
right and unreadable.

### Added

- **A Models screen** (`ModelsActivity`) from the drawer's MODEL row and the header, with **On
  this phone** (per-model cards plus a storage summary) and **Available to download** (the
  catalog, ranked best-fit-first).
- **One card layout for both lists**, so every fact lands in the same place: name, a fixed
  facts line (`1.24B · Q4_0 · 773 MB`), pills, reason, actions.
- **KleidiAI reach as a pill** - filled when the quantization reaches Arm's kernels, dashed
  when it does not, matching the Bench app's existing convention.
- **One solid emphasis per card**: `ACTIVE` for an installed model, `RECOMMENDED` for the single
  best catalog entry, in the same position.
- **Actions on the card**: LOAD / DELETE, or DOWNLOAD / RESUME with a real progress bar and stop.

### Changed

- **A downloaded catalog entry is no longer listed twice** - it appears only under *On this
  phone* rather than in both lists with two different actions.
- Import moved onto the Models screen; the engine stays in the chat screen, so a pick returns
  as an activity result.

## [3.2.0] - 2026-07-22

**A fresh install had nothing to talk to.** ENTITY has always required the user to supply their own
`.gguf`: find a model host in a browser, judge a quantization and a parameter count against the
phone by eye, download it, then import it through the file picker. That is a reasonable ask of a
developer and an unreasonable one of everyone else, and it is the single step where a first run
most often ended. The model picker now offers a small curated catalog that does the judging on the
device, downloads the file, and loads it when it finishes. Importing from storage is unchanged.

### Added

- **Model catalog in the picker** (`ModelCatalog`). Seven entries across Qwen2.5 0.5B/1.5B and
  Llama 3.2 1B/3B, leaning Q4_0 and Q8_0 because those are the two types Arm's KleidiAI kernels
  accelerate. One K-quant is included on purpose, and its row says plainly that it misses KleidiAI
  and falls back to ggml's Arm repack kernels - the same advice the model info card already gives
  after loading.
- **Device-aware fit assessment.** Each row is tagged RECOMMENDED / GOOD FIT / FITS / TIGHT /
  TOO BIG for the phone in hand, with a one-line reason naming the quantization and the ISA it
  will actually reach on this CPU. The flags come from `DeviceOptimizer.cpuFeatures()`, i.e. the
  backend variant ggml dlopened for this device, so the row describes kernels that will really
  run rather than what the silicon nominally supports. Fit is computed against **total** RAM, not
  free RAM: free RAM swings with whatever else is running and would make the same phone report a
  different verdict minute to minute.
- **Resumable, cancellable download** (`ModelDownloader`). Bytes land in a `.part` file and a retry
  continues with an HTTP `Range` request instead of starting over; a `200` response to a ranged
  request is treated as the server declining to resume and restarts cleanly. The file only takes
  its real `.gguf` name once its length matches the catalog's expected size, so a truncated
  download can never be mistaken for a loadable model, and because `ModelStore.scan()` matches on
  the `gguf` extension a half-finished file never appears in the picker or in Settings → Models.
  Progress reuses the existing load bar; a transfer can be sent to the background or stopped.
- **The finished model loads straight into the conversation** (guarded on the activity still being
  at least STARTED, since loading drives this screen's UI), so downloading is one flow rather than
  download-then-go-find-it.

### Changed

- **The empty-state dialog leads with Download.** A phone with no models cannot import one, so the
  first-run dialog now offers Download first and Import second.
- The app now declares `INTERNET` and `ACCESS_NETWORK_STATE`. They are used only by the catalog,
  and only on an explicit tap. Inference, prompt processing, generation, chat storage and runtime
  metrics never touch the network, and ENTITY still runs with no connection at all once a model is
  present.

### File comparison (3.1.0 → 3.2.0)

| File | Change |
|---|---|
| `ModelCatalog.kt` | New. Catalog entries, fit rules, recommendation, `featureFlags()` bridge. |
| `ModelDownloader.kt` | New. Ranged/resumable HTTP download into `.part`, verified rename. |
| `MainActivity.kt` | `showModelPicker()` gains Download; new `showCatalog()`, `downloadModel()`. |
| `AndroidManifest.xml` | `INTERNET`, `ACCESS_NETWORK_STATE`. |
| `strings.xml` | Catalog and download strings; empty-state body mentions Download. |
| `ModelCatalogTest.kt` | New. Catalog shape, fit rules, recommendation, `featureFlags()`. |

## [3.1.0] - 2026-07-21

**Benchmark history: every run the chat app finishes is now kept on the phone.** The in-app
benchmark could produce a result and lose it - navigating away, or the system reclaiming the
activity behind the file picker, left nothing behind, and the only way to keep a run was to export
its CSV in that moment. The standalone Bench app has kept a browsable history since v1.1.0; the
chat app now does the same, so both apps behave the same way after a run finishes.

### Added

- **Autosaved benchmark history** (`BenchHistory`, `BenchHistoryActivity`). Both the three-arm
  ablation and the sustained thermal test write two files the moment they complete - the same
  per-pass CSV the export button builds and the same summary the COPY button emits - plus one
  summary line in an `index.jsonl` the list reads. There is no save button to forget.
- **A history screen**, reachable from the drawer (TOOLS → BENCHMARK HISTORY) and from the
  benchmark screen itself. Newest first, showing model, date, arm count or sustained duration,
  charging state, and the decode delta over naive. Tap opens the saved run, long-press deletes;
  a saved run can be copied or re-exported to CSV at any time, and there is a delete-all.
- **Re-export is a file copy.** The CSV is written at save time, so exporting an older run cannot
  hit the empty-file failure the live export had to be hardened against in v2.1.0 - there is no
  in-memory result left to lose.

### Fixed

- **Back buttons on Settings, About, Benchmark and History now meet the 48 dp touch-target
  minimum** and carry a content description, so they no longer announce as "less than" to
  TalkBack. They were 14 sp glyphs with 6 dp of vertical padding, about 31 dp tall.

## [3.0.3] - 2026-07-20

**Chat now measures whether the phone is keeping up, instead of assuming it can.** Streaming a
reply rebuilds the message's `StaticLayout` on every repaint - full text measurement and
line-breaking, on the main thread, competing with the four decode threads pinned to the same
cores. Previous versions repainted on a fixed 120 ms clock regardless of whether frames were
landing. A live frame-interval measurement (a `Choreographer` callback running only while
generating) now drives that interval directly: a phone holding its refresh rate stays at the
floor, one measurably missing frames backs off to a slower repaint and a slower telemetry sample,
and the metrics graph sheds anti-aliasing, area fill and curve smoothing under the same signal.

### Added

- **Frame-health measurement while generating** (`renderInterval()`, `strained()` in
  `MainActivity`). The pace is derived from measured frame interval, not text length or a device
  tier - length is a bad proxy (the same reply that stalls a budget phone is nothing to a
  flagship), and a device that never drops a frame runs at the floor interval forever.
  `MetricsGraphView.setStrained()` drops anti-aliasing, fill and smoothing under the same signal
  so the cycles go to decode instead of to the picture of decode.

### Fixed

- **Chat auto-scroll now follows the stream only while the reader is already at the bottom**,
  instead of calling `scrollToPosition` on every repaint - stops fighting anyone scrolled up to
  re-read, and skips a layout pass when the tail is off-screen.
- **Process CPU% is now measured over a minimum 400 ms window** (`CPU_WINDOW_MIN_MS`) instead of
  every call; a call inside that window reuses the last measured value rather than dividing by a
  near-zero interval and reading as a nonsense percentage.
- **Metrics graph now plots samples across the full width of whatever data exists**, instead of
  anchoring to the 120-slot buffer capacity - fixed a spike jammed against the right edge during
  the first minute of a session.
- Replaced the remaining em dashes with plain hyphens across UI strings and error messages
  (`strings.xml`, `InfoActivity`, `BenchmarkActivity`).

### Upgrade notes

- No inference-path, thread-derivation or pinning changes; every published CMF and OPPO benchmark
  result carries over.
- Preferences, conversations, KV session files and imported models carry over in place
  (versionCode 11 -> 12, same signing key): `adb install -r` upgrades without uninstalling.

## [3.0.2] — 2026-07-20

**The in-app benchmark derived its thread count from a stale rule, and a flagship exposed it.**
v2.4.0 moved Auto's generation thread count to a topology rule — the cores whose
`cpuinfo_max_freq` sits within 10% of the fastest — and raised the clamp from 4 to 6. The native
engine was changed. The benchmark screen's copy of that rule was not: it kept computing
`online cores − 2`, which returns the same 4 on a 4+4 phone only because the old clamp happened to
be 4. On a 2+6 flagship (Galaxy S26 Ultra: 6× 3.628 GHz + 2× 4.742 GHz) the native side derives
**2** threads while the benchmark's copy returned **6**.

**Chat and inference speed are unaffected.** The stale rule lived only in `BenchmarkActivity`;
real generation always went through `init_context()` in `ai_chat.cpp`, which has been correct
since v2.4.0. This changes what the benchmark measures and reports, not how the app runs.

### Changed

- **Rounded corners across the interface.** Sections, cards, chat bubbles, buttons, dialogs and
  the progress track now share a single 10 dp radius (`@dimen/mono_radius`), replacing v3.0.0's
  hard square corners. One radius for every surface, so a pressed control keeps the same
  silhouette as the box it inverts from. Everything else about MONO is unchanged: two colors,
  monospace type, uppercase section labels, and press feedback as a hard color inversion. The
  hairline rules between regions stay square — they are 2 dp lines, not surfaces.

### Fixed

- **The benchmark's threads-only arm now holds the thread count at Auto's real value.** It
  delegates to `DeviceOptimizer.topClusterCoreCount()` — the same top-frequency-cluster rule the
  native side and the standalone Bench app already use — instead of restating it. On any device
  where the two disagreed, naive → threads-only → Auto was changing two variables between the
  second and third arm, which is the exact attribution error the three-arm design exists to
  prevent. Unaffected on 4+4 devices, where both rules returned 4.
- **Exported CSV metadata now reports the thread count the engine actually used.**
  `threads_optimized` was written from the stale mirror, so an export could claim 6 threads for a
  run that executed on 2.
- Added the 2+6 flagship case to `DeviceOptimizerTest`, the topology the v2.4.0 notes flagged as
  expected but untested.

## [3.0.1] — 2026-07-20

**Performance fix: in-chat decode speed with live metrics visible.** With the metrics graph (or
stats bar) shown, chat decode dropped from ~18 to ~14 tok/s on the reference phone. Cause: the
metrics pipeline ran once per generated token on the main thread — three binder IPCs per token
(battery intent, current draw, memory info) plus a full seven-series graph redraw — and that work
competed with the four decode threads pinned to the big cores. The graph's colors were innocent;
the per-token sampling cadence was the cost.

### Fixed

- **Live metrics now sample on a fixed 500 ms clock instead of per token.** Battery/memory
  reads and graph redraws drop from ~18/s to 2/s during generation, returning in-chat decode
  to benchmark-level speed with the graph visible. Engine, thread derivation and pinning are
  untouched — standalone bench numbers were never affected.
- The metrics graph window is now time-based: 120 samples × 500 ms ≈ the last 60 seconds,
  instead of the last 120 tokens.
- Final stats bar / header chips refresh once at generation end, so the displayed tok/s is
  exact rather than up to half a second stale.

## [3.0.0] — 2026-07-18

**MONO: full UI remake in the ENTITY Bench design language.** Every screen rebuilt from scratch
around the bench app's two-color system: paper and ink only (white/black, `values-night` inverts),
square corners, monospace type, uppercase section labels, and press feedback as a hard color
inversion instead of ripples. Major version because the interface is a remake — the toolbar and its
overflow menu are gone. No inference-path changes, so every published benchmark number carries over.

### Added

- **Left navigation drawer** replaces the toolbar menu entirely. NEW CHAT and the conversation
  list live in one section (tap to switch, long-press to rename/delete, EDIT for multi-select
  delete; the active conversation renders inverted — the design's selection idiom), plus MODEL
  (switch model, model info), TOOLS (benchmark, share chat) and SETTINGS / ABOUT.
- **Settings rebuilt and expanded** into six sections, absorbing everything that used to hide
  in the overflow menu (About stays outside Settings, in the drawer):
  - *Theme* — System / Light / Dark segmented control (was a menu submenu).
  - *Interface* — chat text size (Small / Medium / Large, new), animations, app icon.
  - *Live metrics* — stats bar, metrics graph, graph style (fill area, smooth lines) and all
    seven series toggles (all were menu items).
  - *Chat* — system prompt editor, haptic feedback (new), keep screen on while generating (new).
  - *Inference* — auto/manual tuning sliders, efficiency mode, re-run device optimization
    (behavior unchanged).
  - *Data* (new) — manage imported models (per-file delete with size totals, active model
    protected), export all chats as plain text, clear all conversations.
- **Daily-driver features**: chat text size, haptic ticks on send and on reply completion,
  keep-screen-on during generation, model storage management, full-chat export, clear-all.
- Model name lives in the header — tap the title block to switch models without opening the
  drawer. Live battery °C and free-RAM readouts stay in the header.

### Changed

- **Theme**: two colors total (`mono_bg` / `mono_fg`), exactly like ENTITY Bench. Theme choice is
  applied via AppCompatDelegate before any activity inflates, so there is no wrong-mode flash.
- **Chat**: user messages are solid ink blocks with inverted text; assistant messages are bordered
  boxes on paper; bubbles cap at 84% of the list width on any screen size. Markdown inline code
  renders as reverse video and fenced code blocks get a hard left ink bar — no gray washes.
- **Metrics graph** is deliberately the one colored surface in the mono UI: seven overlaid series
  need hue to stay readable, so the data keeps its per-series colors while every piece of chrome
  around it is ink.
- **Dialogs** are square, bordered and monospace, with bold ink action buttons styled explicitly
  at the theme level.
- **Benchmark screen** restyled to the bench app's bordered boxes and segmented pickers; the
  results table scrolls horizontally with fixed columns instead of cramped weighted cells.
  Measurement logic, the three-arm ablation and the CSV schema are untouched.
- Typing indicator, progress bars and status pills are all mono; the KleidiAI advisor
  pill is a solid inversion (ACTIVE) or a dashed box (NOT USED).

### Removed

- The toolbar, its overflow menu and every menu-item toggle (all relocated into Settings), the
  teal accent, every intermediate gray, rounded corners, ripples and the press-scale animator.

### Upgrade notes

- Everything carries over in place: preferences (same keys), conversations, KV session files and
  imported models. versionCode 8 → 9, release-signed with the same key, so
  `adb install -r` upgrades without uninstalling.

## [2.4.0] — 2026-07-17

**KV-cache session reuse and topology-adaptive threads.** Two inference-path changes: multi-turn
TTFT no longer pays for re-decoding the whole history, and the thread count is derived from the
CPU topology instead of hardcoded. On the reference 4+4 phone the derived count is still exactly
4, so every published benchmark number carries over.

### Added

- **KV-cache session reuse**: the active conversation's KV state is saved (llama.cpp
  `llama_state_seq_*` API) to a per-conversation file in app-private storage and restored on
  conversation switch and app restart, instead of re-decoding the full history. The state file
  header records model, context size and system prompt; any mismatch, corruption or size overflow
  falls back silently to the existing re-prime path. State files are deleted with their
  conversation. A TTFT log line per path (restored vs re-primed) makes the gain measurable
  on-device.
- **Topology-adaptive thread count**: Auto's generation thread count is now the size of the top
  frequency cluster (cores with `cpuinfo_max_freq` within 10% of the fastest), clamped to [2, 6],
  instead of a hardcoded 4. A 4+4 big.LITTLE phone still derives 4; a flagship with more than four
  performance cores threads wider. Manual thread settings still override. See
  `docs/OPTIMIZATIONS.md` section 0 for the rule and the expected-untested flagship note.

## [2.3.0] — 2026-07-15

**UI polish and quality-of-life.** A visual pass over the whole app plus the small features a
daily driver needs. No inference-path changes, so every published benchmark number carries over.

### Added

- **Multi-select conversation delete**: the Conversations dialog gains a Select mode with
  checkboxes and a single confirmed bulk delete.
- **Share chat**: exports the current conversation as plain text through the system share sheet.
- **Graph style options** (menu): Fill area and Smooth lines for the live metrics graph. Both are
  decorative, so they obey the Animations setting like every other effect; Animations off keeps
  the plain minimal graph.

### Changed

- **Refined visual system**: hairline borders instead of heavy strokes, neutral assistant bubbles
  with asymmetric corners, pill-shaped input bar, card-grouped Settings and Benchmark screens,
  soft status pills (green/amber) for the KleidiAI advisor on the model info card, consistent
  16 dp spacing rhythm and a tightened type scale. Light and dark themes both reworked; the
  ENTITY teal accent and the metrics identity are unchanged.
- The toolbar reset action now uses a reset glyph instead of a pencil, which read as "edit".
- The sustained-benchmark duration selector spreads its 2/5/10 min options evenly across the row
  instead of overflowing on narrow screens.
- Settings card dividers use consistent 12 dp spacing.

### Fixed

- Benchmark CSV meta: `affinity_naive` now reports `mask_all_cores_effectively_unpinned` instead
  of the misleading `pinned_fast_cores` (the naive arm's mask is the N fastest of N cores, i.e.
  all of them; the behavior was always correct, the label was not).

## [2.2.0] — 2026-07-15

**Sustained thermal benchmark.** The regular benchmark answers "how fast is a cool phone for one
pass". This release adds the question a phone actually poses: does the rate hold once the SoC is
hot? The sustained mode runs back-to-back PP 512 / TG 128 passes for a selectable 2, 5 or 10
minutes per arm, with no cooldown between passes, and records how each arm degrades.

### Added

- **Sustained benchmark mode** on the benchmark screen: threads-only and Auto run back-to-back
  passes for the selected duration (2 / 5 / 10 minutes per arm, 5 default), deliberately without
  the inter-pass thermal cooldown the regular benchmark uses. Heat is the variable under test.
- **Per-pass sustained telemetry**: decode tok/s, Android thermal status, battery temperature and
  power for every pass, in the results table and the exported CSV.
- Sustained CSV gains a per-pass **power (W)** row; headline and notes report the actual pass
  count each arm completed in the window.

### Changed

- The fixed six-pass sustained loop became time-bounded: passes repeat until the selected duration
  elapses (always at least one), keeping the existing 2 s inter-pass gap.

## [2.1.0] — 2026-07-14

**ENTITY's own benchmark disproved ENTITY's flagship optimization, and found two that actually
work.** The v2.0.0 headline credited a +121% decode gain to big-core affinity pinning. The
three-arm ablation shipped in this release measured it: the pinning earns **~0%**. The gain is the
thread count. Meanwhile Arm's KleidiAI — the reason seven CPU backend variants ship — was never
executing at all, because it has kernels only for Q4_0/Q8_0 and every published benchmark used
Q3_K_L.

### Added

- **Three-arm benchmark ablation.** A third configuration between naïve and Auto: **threads-only**,
  Auto's derived thread count with core affinity switched off (no `sched_setaffinity`, no pinned
  thread pool). It is the in-app equivalent of an upstream `llama.cpp -t N` run, and it is what lets
  the result be attributed instead of assumed. The app prints the split under its results table.
- `pinCores` through `InferenceEngine.applyConfig` → JNI `configure()` → `g_pin_cores`. Defaults to
  true, so every shipped path is unchanged; only the ablation arm turns it off. `unpin_all_cores()`
  clears any mask inherited from the previous arm.
- **Effective-affinity logging.** Each arm logs the CPU mask the kernel actually applied
  (`effective cpus` in logcat), so a silently failed `sched_setaffinity` cannot masquerade as
  "pinning earns nothing".
- **KleidiAI advisor.** `FileType.kleidiAiAccelerated` gates the claim on the loaded quantization.
  The model-info card used to print "KleidiAI" unconditionally — telling users their model was
  Arm-accelerated when it was not. It now says so, and quantifies what a non-accelerated quant costs.
- **Per-core CPU frequency sampling** in the benchmark telemetry and CSV, plus perf/little core-clock
  rows in the results table.
- `benchmarks/plot_results.py` and the published charts; `device-result-template.csv` gains
  `kleidiai_accelerated` and `pinning_decode_delta_pct`.

### Changed

- **Prompt processing no longer widens to all cores — it was a regression.** Auto used split thread
  pools to give prompt eval every online core, assuming a compute-bound phase wants all the hardware.
  An A55 is about a third of an A78's throughput, so the widened pool finished late and every GEMM
  waited on the stragglers. Measured on 1B Q4_0: prompt on 4 fast cores **135 tok/s**, spread across
  all 8 **86 tok/s**. Both phases now run on the fast-core thread count.
- Benchmark, README, FAQ, ARCHITECTURE, OPTIMIZATIONS and the release notes no longer credit the
  speed-up to core pinning. The affinity code still ships — it is free, and another SoC may answer
  differently — but it is not what makes ENTITY fast.

### Fixed

- **CSV export silently wrote 0-byte files.** The system file picker comes to the foreground while a
  multi-gigabyte model is resident; Android kills the activity behind it; the recreated instance had
  no result, so `buildCsv(lastResult ?: return)` bailed out — while DocumentsUI had already created
  the file and the app still toasted "CSV exported". This is why no raw per-pass CSV ever survived to
  be published. The CSV is now staged to cache **before** the picker opens and carried through
  `onSaveInstanceState`; a lost result reports an error instead of writing nothing.
- `buildFeatures.buildConfig` enabled. `BenchmarkActivity` reads `BuildConfig.VERSION_NAME` for CSV
  provenance, but AGP 8 does not generate `BuildConfig` unless asked, so the app did not compile.

### Measured (CMF Phone 1, Dimensity 7300, unplugged)

| | v2.0.0 (Q3_K_L, widened prompt pool) | v2.1.0 (Q4_0, fast-core prompt) |
|---|---:|---:|
| Prompt throughput | 38.3 tok/s | **133 tok/s** |
| **Time to first token** | **13,440 ms** | **3,918 ms** |
| Decode throughput | 16.7 tok/s | 14.7 tok/s |

Time-to-first-token improves **3.4×**. Decode gives up ~12%, the bandwidth cost of the larger
quantization. Full record, graphs and limits: [BENCHMARKS.md](benchmarks/BENCHMARKS.md).

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

> **Corrected in v2.1.0:** the numbers below are accurate measurements, but the attribution is not.
> They compare naïve against Auto, which differ in thread count *and* core placement. The three-arm
> ablation later showed the thread count earns the gain and the pinning earns ~0%. What reproduces
> cross-vendor is the *mechanism* (live `cpufreq` ranking), not a pinning benefit.

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
| 3.6.2 | `ENTITY-v24-entity-identity-prompt-20260724-release.apk` (release-signed, ~10 MB) |
| 3.6.1 | `ENTITY-v23-repeat-penalty-20260724-release.apk` (release-signed, ~10 MB) |
| 3.6.0 | `ENTITY-v22-adpf-power-fix-20260723-release.apk` (release-signed, ~10 MB) |
| 3.5.0 | `ENTITY-v21-prefill-threads-placement-latex-20260723-release.apk` (release-signed, ~10 MB) |
| 3.4.1 | `ENTITY-v20-edge-insets-20260722-release.apk` (release-signed, ~10.4 MB) |
| 3.4.0 | `ENTITY-v19-models-screen-colour-20260722-release.apk` (release-signed, ~10.4 MB) |
| 3.3.0 | superseded by v3.4.0 (same day) |
| 3.2.0 | `ENTITY-v18-model-catalog-20260722-release.apk` (release-signed, ~10.4 MB) |
| 3.1.0 | `ENTITY-v17-bench-history-20260721-release.apk` (release-signed, ~10.4 MB) |
| 3.0.3 | `ENTITY-v16-ui-perf-20260720-release.apk` (release-signed, ~10.3 MB) |
| 3.0.2 | `ENTITY-v15-benchmark-thread-derivation-20260720-release.apk` (release-signed, ~10.3 MB) |
| 3.0.1 | `ENTITY-v14-metrics-sampling-fix-20260720-release.apk` (release-signed, ~10.3 MB) |
| 3.0.0 | `ENTITY-v13-mono-ui-refresh-20260718-release.apk` (release-signed, ~10.3 MB) |
| 2.4.0 | `ENTITY-v12-kv-session-adaptive-threads-20260717-release.apk` (release-signed, ~10.3 MB) |
| 2.3.0 | `ENTITY-v11-ui-polish-20260715-release.apk` (release-signed, ~10.3 MB) |
| 2.2.0 | `ENTITY-v10-sustained-thermal-20260715-release.apk` (release-signed, ~10.3 MB) |
| 2.1.0 | `ENTITY-v9-kleidiai-quant-20260714-release.apk` (release-signed) · `ENTITY-v9-kleidiai-quant-20260714-debug.apk` (debug) |
| 2.0.0 | `ENTITY-v8-universal-arm-20260712-1240-debug.apk` (debug, ~49 MB) · `ENTITY-v8-universal-arm-20260712-1240-release.apk` (release-signed, ~9.8 MB) |
| 1.7.0 | `ENTITY-v7-efficiency-thermal-20260712-0120-debug.apk` (debug, ~40 MB) · `ENTITY-v7-efficiency-thermal-20260712-0120-release.apk` (release-signed, ~7 MB) |
| 1.6.0 | `ENTITY-v6-chats-uipolish-20260710-2213.apk` (debug) |
| 1.5.0 | `ENTITY-v5-ui-emptystate-20260704-1610.apk` (debug) |
| 1.4.0 | `ENTITY-v4-icon-chips-20260704-1259.apk` (debug) |
| 1.3.0 | `ENTITY-v3-benchmark-20260703-2118.apk` (debug) |
| 1.2.0 | `ENTITY-v2-modelinfo-progress-20260703-2048.apk` (debug) |
| 1.1.0 | `ENTITY-v1-runtime-graph-settings-20260703-1521.apk` (debug) |
| 1.0.0 | `ENTITY-optimized-single-variant-20260702-2335.apk` (debug) |
