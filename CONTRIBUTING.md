# CONTRIBUTING

How to pick this project up and keep going with it. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) first
if you haven't — this file assumes you know the shape of the codebase.

## Repo layout

```
app/entity.android/     the Android Studio / Gradle project
  app/                   com.example.llama — UI module (Activities, views, adapters)
  lib/                   com.arm.aichat — inference library module (Kotlin JNI wrapper + native)
    src/main/cpp/        ai_chat.cpp, CMakeLists.txt — the native inference layer
apk/                     prebuilt debug APKs, one per tagged version
releases/                copy-paste-ready GitHub Release notes, one per version
benchmarks/              measured results (BENCHMARKS.md) + raw logs/CSVs
docs/                    this file, ARCHITECTURE.md, BUILD.md, OPTIMIZATIONS.md, + submission docs
scripts/                 Termux/llama.cpp CLI benchmark + chat scripts (not part of the app)
screenshots/             app screenshots used in README/github.md
CHANGELOG.md             per-version history, Keep a Changelog format
github.md                hackathon submission write-up
```

The app **only** builds from inside a llama.cpp checkout (`examples/entity.android/`) — see
[`BUILD.md`](BUILD.md) before touching the native layer.

## Coding conventions observed in this codebase

Match these — the codebase is intentionally small, direct, and light on ceremony:

- **Kotlin, not defensive Java-in-Kotlin.** Prefer `when` expressions, data classes, `runCatching`,
  extension-style small private functions over large branchy methods. See `Settings.kt` or
  `IconStyle.kt` for the target shape of a small file.
- **Minimal, purposeful comments.** Comments explain *why*, not *what* — e.g. the comment above
  `pin_to_fast_cores()` explains why it's called on every entry point (thread migration), not that
  it "pins to fast cores" (the function name already says that). Don't narrate obvious code.
- **No dependency injection framework.** `AiChat`/`InferenceEngineImpl` uses a manual singleton
  (`getInstance`). `SharedPreferences` is read fresh where needed rather than pushed through a
  view-model layer. Keep new code consistent with this — don't introduce Hilt/Dagger/Koin for a
  codebase this size.
- **`SharedPreferences` keys live in one place.** All tunable-config keys are constants in
  `Settings.kt`, read via `Settings.load(prefs)`. If you add a new tunable, add its key and default
  there, not inline in an Activity — that's exactly the drift `Settings.kt`'s header comment warns
  against.
- **JNI naming is mechanical.** Native functions are
  `Java_com_arm_aichat_internal_InferenceEngineImpl_<name>` and the Kotlin `external fun` in
  `InferenceEngineImpl.kt` must match 1:1, including parameter types. `@FastNative` is reserved for
  short, frequent calls (setters, per-token generation) — see the comment above the `external fun`
  block in `InferenceEngineImpl.kt` for why long calls omit it.
- **No emoji, no marketing copy in code or comments.** UI copy in `InfoActivity`/strings is plain
  and factual, matching the rest of the docs.

## Adding a new Activity / screen

1. Add the Kotlin file under `app/src/main/java/com/example/llama/`, extending
   `AppCompatActivity`. Follow `SettingsActivity`/`InfoActivity` as the template for a simple
   screen (toolbar with back navigation via `setDisplayHomeAsUpEnabled` + `setNavigationOnClickListener`).
2. Add the layout under `app/src/main/res/layout/activity_<name>.xml`.
3. Register it in `app/src/main/AndroidManifest.xml`:
   ```xml
   <activity android:name=".YourActivity" android:exported="false"
       android:parentActivityName=".MainActivity" />
   ```
4. If it needs the inference engine, get it the same way every other screen does:
   `AiChat.getInferenceEngine(applicationContext)` — don't construct your own instance; it's a
   singleton for a reason (one native model loaded at a time).
5. If it needs shared config, read/write through `Settings.kt`, not ad hoc preference keys.
6. Wire navigation to it from `MainActivity.onOptionsItemSelected` (menu) or wherever makes sense,
   and add the corresponding menu entry in `app/src/main/res/menu/main_menu.xml` if applicable.

## Touching the native layer

1. Edit `lib/src/main/cpp/ai_chat.cpp` (or add a new `.cpp`, updating `CMakeLists.txt`'s
   `add_library` sources).
2. New JNI-exported functions must follow the mangled naming convention
   (`Java_com_arm_aichat_internal_InferenceEngineImpl_<name>`) and get a matching `external fun` in
   `InferenceEngineImpl.kt`.
3. Rebuild the `.so`:
   ```bash
   cd llama.cpp-master/examples/entity.android
   ./gradlew :lib:externalNativeBuildDebug --no-daemon --console=plain
   # or just rebuild the whole app — Gradle re-triggers CMake when C++/CMakeLists.txt changes:
   ./gradlew :app:assembleDebug --no-daemon --console=plain
   ```
4. If you change global state touched by both the chat path and `benchModel()` (e.g. adding a new
   `g_*` variable), check whether `benchModel()` needs to save/restore it — it builds a throwaway
   context and previously leaked exactly this way twice (context bounds in v1.3.0, CPU affinity in
   v1.4.0 — see `CHANGELOG.md`). Grep for the existing `saved_*` pattern in `benchModel()` before
   adding new mutable native state.
5. Validate on-device: `adb logcat -s AiChat:* ai-chat:*` while loading a model and generating —
   confirm `init_context` logs the expected thread count/context size and
   `"pinned inference to %d fast cores"`.

## Validating a change with the in-app benchmark

Any change touching threading, affinity, context sizing, or the backend build flags should be
checked against the in-app benchmark before and after:

1. Build and install your change.
2. Load a model (Llama-3.2-1B-Instruct-Q4_0 is the fastest round-trip for iteration).
3. ⋮ → **Benchmark** → **Run benchmark**.
4. Compare the "Optimized" column's decode t/s and tok/W against a baseline run from `main` on the
   same model, same phone, unplugged, screen on, no other apps active (charging invalidates power
   numbers — the app will warn and hide them).
5. For anything that might interact with sustained/contention behavior, also check
   `benchmarks/BENCHMARKS.md`'s methodology and consider a CLI-side re-run with the Termux scripts
   in `scripts/` if you want numbers comparable to the existing dataset there.

Don't trust a single run — `benchmarks/BENCHMARKS.md` repeats measurements (2–3x) and reports
mean ± stddev for a reason; phone thermal state and background load both move the numbers.

## Good first issues / next steps

- **Stripped release build.** The APKs in `apk/` are all debug builds (~100 MB, symbols not
  stripped). Producing and publishing a `assembleRelease` build (see
  [`BUILD.md`](BUILD.md#3-build)) would give a materially smaller download — good first PR.
- **System-prompt / KV-cache reuse across turns.** `processSystemPrompt` currently re-decodes the
  system prompt into the KV cache once per conversation, which is correct, but there's no
  persistence across app restarts or model switches. Caching/reusing a warmed KV state (or at
  least the tokenized system prompt) would cut time-to-first-token on repeated sessions.
- **Prompt caching / reuse to cut TTFT.** Related: for multi-turn chats, `decode_tokens_in_batches`
  redecodes the full user turn each time; investigate whether llama.cpp's session
  save/restore (`llama_state_*` APIs) can skip redundant prefix work.
- **More devices.** `build_fast_cpu_set()` is already SoC-agnostic (ranks by live `cpufreq`), but
  the compiled backend (`GGML_CPU_ARM_ARCH`) is not — see
  [`BUILD.md`](BUILD.md#device-specific-configuration-adapting-ggml_cpu_arm_arch). Validating on a
  second big.LITTLE SoC (different vendor/core mix) would be strong evidence the approach
  generalizes, not just tunes to one phone.
- **Realtime priority, done safely.** See
  [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md#5-big-core-pinning-under-contention--app-vs-cli-read-this-one-carefully)
  for why the app doesn't currently request `SCHED_RR`. If you want to explore it, gate it behind
  an explicit user opt-in and a short burst (not the whole generation), and test across multiple
  OEM skins — some restrict realtime scheduling for non-system UIDs.
- **Universal / multi-SoC backend option.** Expose `GGML_CPU_ALL_VARIANTS=ON` as a build flavor
  (rather than editing `lib/build.gradle.kts` by hand) so the same source tree can produce either a
  device-tuned build or a portable one.
- **Speculative decoding.** Documented as RAM-gated on a 6 GB device in
  `docs/DOCUMENTATION.md` (Part 5) — worth revisiting on 8–12 GB phones or with a sub-0.5B draft
  model.

## Where to look before asking

- Native behavior questions → [`ARCHITECTURE.md`](ARCHITECTURE.md) and read `ai_chat.cpp` directly;
  it's ~680 lines and single-file.
- "Is X actually true about the app?" → [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md) is written to be
  checkable against the source; if a doc and the code disagree, the code wins — please file an
  issue/PR fixing the doc.
- Build/toolchain issues → [`BUILD.md`](BUILD.md#common-build-issues).
- What changed and when → `CHANGELOG.md` (per-version, with a "File comparison" section per
  release showing exactly which files moved).
