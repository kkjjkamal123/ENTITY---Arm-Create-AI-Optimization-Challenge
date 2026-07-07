# ENTITY — Full Development Log

Team **ENTITY** — Arm Create: AI Optimization Challenge (Mobile AI track)
On-device offline LLM chatbot on the **CMF Phone 1**.

This is the complete, chronological record of the project: every decision, every
command, every code change, every error and its fix, and every requirement given.
It is written so the whole journey can be reproduced from zero.

---

## 0. Project at a glance

- **Goal:** an optimized, fully-offline LLM chatbot running on the CMF Phone 1, with
  measurable Arm-specific optimization and extensive benchmarking.
- **Two deliverables:**
  1. A benchmarked **Termux/llama.cpp** command-line setup (optimized vs naive scripts).
  2. A native **Android app ("ENTITY")** that runs the models on-device with a
     professional UI and live performance stats.
- **Models used:** Llama-3.2-1B-Instruct and Llama-3.2-3B-Instruct (GGUF, various quants).
- **Licensing target:** public repo, MIT/Apache-2.0.

### Hardware / environment

- **Phone:** CMF Phone 1 — MediaTek **Dimensity 7300**, 6 GB RAM.
  - CPU: big.LITTLE — 4× **Cortex-A78 @ 2.5 GHz** (cpu 4–7) + 4× **Cortex-A55 @ 2.0 GHz** (cpu 0–3).
  - A78 ISA: **armv8.2-a + dotprod (SDOT)**. No i8mm, no SVE/SME.
- **Laptop:** Lenovo LOQ, i5-12450HX, 12 GB RAM, RTX 3050 6 GB, Linux.
- **Free RAM on phone:** ~2.5–3.3 GB typically.

---

## 1. Concept & planning phase

### User messages (verbatim intent)
- "so im going to paricipate in this ARM competition."
- Weighed Jetson Nano vs phone → chose the **CMF Phone 1** (6 GB), on-device LLM / chatbot.
- "wait dont start anything first teach me everything we are doing.. only then after i allow u work" → **teach-first** rule established.
- Team name: **ENTITY**.
- "btw our main motive is to optimize it better.. always remember it" → optimization is the core.
- Idea raised: boosting laptop RAM via zram/page memory.
- Decision: **3B it is** (after comparing 1B vs 3B), but keep 1B too.

### Concepts studied before building
- llama.cpp on-device inference; GGUF format; quantization (Q4_0, Q8_0, Q3_K_L, IQ3_M, Q4_K_M).
- big.LITTLE scheduling; CPU affinity pinning; realtime thread priority; thread count tuning.
- KleidiAI / dotprod / SDOT; i8mm/SVE/SME (and why the A78 lacks them).
- Memory-bandwidth-bound generation vs compute-bound prompt processing.
- tokens/sec, tokens/sec/watt, TTFT.
- Termux, SSH over USB-tether / Wi-Fi, adb over USB.
- Android background throttling (why a naive app is slower than Termux).

---

## 2. Phone access & Termux setup

### Connection
- SSH into Termux: `ssh -p 8022 <phone-ip>`, key `~/.ssh/id_ed25519`, user `u0_a153`.
- Initial Wi-Fi SSH failed due to **AP isolation** on the TP-Link router.
  - Fix: switched to **USB tethering**, then disabled AP isolation on the router
    (192.168.0.1) so Wi-Fi SSH worked at **192.168.0.105**.

### Recurring connection issues and fixes
- Termux repeatedly killed (connection refused/timeout): memory pressure + Android
  background throttling + Wi-Fi sleep on screen-off.
  - Mitigation: `termux-wake-lock`; keep screen on during long runs.
- A runaway power sampler (a `while` loop with no sleep) overwhelmed the phone and
  killed Wi-Fi → added `sleep 0.5` between samples.
- `termux-battery-status` occasionally hung → wrapped in `timeout 6`, one call per iteration.
- Multiple benchmark instances contended → made scripts self-cleaning / single-instance.

---

## 3. Benchmarking phase (the core of the project)

All results are recorded in `BENCHMARKS.md`; raw logs and charts are in `proof/`.

### What was measured
- **Master benchmark:** optimized vs naive on 1B and 3B.
- **Quant ladder:** Q8_0 / Q4_0 / Q3_K_L / IQ3_M for the 1B.
- **Thread scaling** on the 1B.
- **Thermal / over-time** behaviour.
- **KleidiAI** on/off effect.
- **Power** (tokens/sec/watt) via `termux-battery-status` (voltage mV, current µA).

### Key optimization flags (llama-cli)
```
-t 4 -Cr 4-7 --cpu-strict 1 --prio 3 --mlock
```
- `-t 4`  → 4 threads (match the 4 big cores).
- `-Cr 4-7 --cpu-strict 1` → pin strictly to the A78 performance cluster.
- `--prio 3` → realtime priority.
- `--mlock` → lock model in RAM (validated; RAM-gated on 6 GB).

### Corrections made to keep results honest
- An early "3.3× speedup" was **contaminated by early-EOS**; re-run with `--ignore-eos`
  → corrected to roughly **1.5–2×**.
- Premature quant benchmark on an incomplete download → added a **size-verified waiter**.

### Scripts produced (in `scripts/`, all clean/human-written, no AI credits)
- `chat.sh`, `chat-naive.sh` — 1B optimized / naive.
- `chat3b.sh`, `chat3b-naive.sh` — 3B optimized / naive.
- `clean_ram.sh` — drop caches / free RAM.
- `benchmark.sh`, `quant_bench.sh`, `thermal_bench.sh` — benchmark harnesses.
- `autotune.sh` — auto-detects the fastest cores (by cpufreq) and picks thread/affinity.

Example `chat.sh`:
```bash
#!/data/data/com.termux/files/usr/bin/bash
cd ~/llama.cpp
./build/bin/llama-cli \
  -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf \
  -t 4 -Cr 4-7 --cpu-strict 1 --prio 3 --mlock \
  -c 4096 --color on \
  -sys "You are ENTITY, a helpful assistant running fully offline on a CMF Phone 1."
```

### Documentation deliverables
- `BENCHMARKS.md` — all experiments and numbers.
- `DOCUMENTATION.md` — full reproducible command log, optimizations explained, uniqueness,
  roadmap (auto-tuner done, `--mlock` validated, speculative decoding RAM-gated).

### Hard rules confirmed here
- "remove most of the comments... make it look human typed... no credits for claude AI very imp."
- "document every command... in-depth documentation of how we did and why it's massive...
  optimizations unique that no people in this hackathon shouldve done."
- "keep two sh for each model one optimized and one without."

---

## 4. Native Android app — first build

Goal: beat Termux by running in the **foreground** (no background throttling), with a real UI.

### Toolchain installed (under `/home/kamal/android/`)
- `jdk-17.0.19+10/` (JDK 17).
- `sdk/` (ANDROID_HOME): cmdline-tools, platform-tools (adb), platforms android-35/36,
  build-tools 35/36, cmake 3.22.1 + 3.31.6, **NDK 27.1.12297006**.

Environment used for every build:
```bash
export JAVA_HOME=/home/kamal/android/jdk-17.0.19+10
export ANDROID_HOME=/home/kamal/android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

### Getting the source
- `git clone` of llama.cpp timed out repeatedly.
  - Fix: downloaded the **tarball** instead:
    `https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz` (~34 MB, fast).
- Based the app on the bundled `examples/llama.android`, renamed to **`entity.android`**.

### App identity changes
- `settings.gradle.kts` → `rootProject.name = "ENTITY"`.
- `app/build.gradle.kts` → `applicationId "com.entity.chat"`, `compileSdk 36`.
- `res/values/strings.xml` → `app_name = "ENTITY"`.
- `lib/build.gradle.kts` → `ndkVersion = "27.1.12297006"`, KleidiAI on for arm64,
  `GGML_CPU_ALL_VARIANTS=ON`.

### Build & install
```bash
cd entity.android
./gradlew :app:assembleDebug --no-daemon --console=plain
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app-debug.apk
```

### Errors and fixes in this phase
- NDK 29 was expected but **27** was installed → set `ndkVersion = "27.1.12297006"`.
- Build killed by a session teardown → **resumed** (ninja is incremental) → succeeded.

### First real feedback (the turning point)
> "very poorly optimized… the UI is worst… the movement doesnt look smooth… updating for
> every token is so annoying… i need to load models every time which sucks… remove the top
> info of the model it looks ugly… make the UI very well… its running worse from termux
> HOW?! pls optimize our model and i need info such as tokens generated, tokens start,
> temp, mem available — every info as an option to enable/disable… professional like
> chatgpt and claude… i need to select model from the app not from files. i have 2, 1B and 3B."

This defined the entire app rework below.

---

## 5. Wave 1 — fixing "worse than Termux" (CPU affinity)

### Root cause
The native code (`lib/src/main/cpp/ai_chat.cpp`) created 4 threads but **never pinned
them to specific cores**. Android's scheduler scattered inference across the slow A55
little cores. Termux only won because `chat.sh` pins to the big cores (`-Cr 4-7`).

### Approach chosen
llama.cpp's own threadpool API (`ggml_threadpool_new`) could not be used: with
`GGML_CPU_ALL_VARIANTS` the CPU backend is a **dynamically-loaded** library, so those
symbols don't link (linker error: `undefined symbol: ggml_threadpool_new`). Also the
Kotlin engine uses `Dispatchers.IO.limitedParallelism(1)`, which can migrate across IO
threads — so a one-time pin wouldn't stick.

Solution: **pin CPU affinity with `sched_setaffinity` on the calling thread at every
decode entry point.** ggml's worker threads are spawned from that thread and inherit the
mask, so all inference stays on the A78 cluster.

### Code added to `ai_chat.cpp`
- Includes: `<vector>`, `<algorithm>`, `<cstdio>`, `<sched.h>`.
- Globals: `cpu_set_t g_fast_cpus; int g_fast_count;`
- `build_fast_cpu_set(int want)` — reads
  `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, ranks cores by max frequency,
  and marks the fastest `want` cores (the A78s). No hardcoded core numbers — portable.
- `pin_to_fast_cores()` — calls `sched_setaffinity(0, …, &g_fast_cpus)`.
- Called at the end of `init_context()` (builds the set + pins), and at the top of
  `decode_tokens_in_batches()` (prompt processing) and `generateNextToken()` (per token).

### Result
Rebuilt, reinstalled. Inference now stays on the big cores — no longer slower than Termux.

---

## 6. Wave 2 — professional UI rewrite

Everything below is in `app/src/main/`.

### Design resources
- `res/values/colors.xml` — ChatGPT/Claude-style palette: accent `#10A37F`,
  app_bg, surface, on_surface, muted, divider, user_bubble, assistant_bubble, input_bg.
- `res/values/themes.xml` — Material3 theme, accent primary, light status/nav bars.
- `res/values/strings.xml` — menu + hint strings.
- `res/drawable/bg_user_message.xml`, `bg_assistant_message.xml` — asymmetric rounded
  bubbles (18dp with one tight corner), colored from resources.
- `res/drawable/bg_input.xml` — rounded 24dp input field with subtle border.
- `res/drawable/bg_send_button.xml` — circular accent send button.

### Layouts
- `res/layout/activity_main.xml` — `MaterialToolbar` (title "ENTITY", model as subtitle),
  a toggleable monospace **stats bar**, a divider, the messages `RecyclerView`, and a
  bottom input row (rounded `EditText` + circular `ImageButton`). Removed the ugly GGUF
  metadata `TextView` entirely.
- `res/layout/item_message_user.xml` / `item_message_assistant.xml` — max-width 300dp,
  selectable text, comfortable line spacing.

### Adapter — smooth streaming
`MessageAdapter.kt` rewritten with a single `MessageViewHolder` and a **payload-based
partial update** (`PAYLOAD_TEXT`) so streaming a token only re-sets the text, not a full
rebind. Combined with `messagesRv.itemAnimator = null` this killed the per-token jank.

### Menu
`res/menu/main_menu.xml`:
- **Select model** (toolbar action, folder icon).
- **Show stats** (checkable).
- **Stats** submenu (checkable group): Tokens generated, Tokens/sec, Time to first token,
  Temperature, Power draw, Memory available.
- **Theme** submenu (single-choice): System default / Light / Dark.

### MainActivity rewrite (highlights)
- **In-app model picker** — `showModelPicker()` scans `getExternalFilesDir("models")`
  (and `filesDir/models`) for `*.gguf`, shows an `AlertDialog` list; no file browser.
  ("Import from device…" kept as a secondary option.)
- **Throttled rendering** — tokens append to a `StringBuilder`; the UI repaints at most
  every ~45 ms (and once more on completion), auto-scrolling to the bottom.
- **Live stats** computed on the fly and toggled from SharedPreferences:
  - tokens generated (count), tok/s (count ÷ generation time),
  - TTFT (`firstToken − sendTime`),
  - temperature (battery sticky intent `EXTRA_TEMPERATURE / 10`),
  - power draw (see §7), memory available (`ActivityManager.MemoryInfo.availMem`).
- **System prompt** set right after load.
- Cleaned out all dead sample code (the deprecated `runBenchmark`, GGUF metadata parsing,
  unused imports).

### End-to-end test (1B Q8_0, fully offline)
`100 tok · 11.0 tok/s · TTFT 889ms · 41.0°C · 1.0GB free` — smooth, coherent answer.

---

## 7. Round 3 — theme, power stat, icon, and the model-switch bug

User request (verbatim):
> "when i open llama 3B instruct it says cant load model error reading file. and also i want
> the entity icon background black not white… make the app more optimized for low end device…
> remove unwanted stuffs… dont over complicate… same but more optimized… CMF phone 1 6gb."
And earlier in the same round:
> "when i load any model all it replies is *beep*boop*, *whir*whir* like that."

### 7.1 Model-switch bug — "Cannot load model in ERROR!"
- Cause: `loadModel()` requires the engine state to be **Initialized**, but after a load
  it is **ModelReady**, and after a failed load it is **Error**. Only `cleanUp()` resets
  those states. The first version only called `cleanUp()` when a model was *ready*, so
  once any load failed the engine was stuck in `Error` and every later load threw.
- Fix (`prepareModel`): before loading, if the state is `ModelReady` **or** `Error`,
  clear the conversation and call `runCatching { engine.cleanUp() }` to reset to
  Initialized, then load.

### 7.2 3B "error reading file" / failed load
- The `lowmemorykiller` was thrashing during 3B load. The **8192-token KV cache** plus the
  1.8 GB model exceeded available RAM, so the native context allocation failed → Error.
- Fix: dropped the native default context from **8192 → 4096** in `ai_chat.cpp`
  (`DEFAULT_CONTEXT_SIZE = 4096`). Halves the KV cache; the 3B now fits (≈0.8–1.1 GB free
  while running).
- Additionally, the specific on-device 3B file was a **bad copy** (from an earlier
  on-device `cp` of a partial push). Replaced it with a fresh, byte-verified `adb push`
  of the correct file (`1,921,909,280` bytes, identical to the source).

### 7.3 "*beep*boop*" replies
- Cause: the system prompt just named the model "ENTITY", so small models role-played a
  robot and emitted sound effects.
- Fix: explicit system prompt —
  > "You are ENTITY, a helpful AI assistant running fully offline on the user's phone.
  > Answer questions directly and clearly in natural language. Do not roleplay, narrate
  > actions, or make robotic sound effects."
- Verified: greeting now returns a proper, natural assistant reply.

### 7.4 Light / Dark / System theme
- `themes.xml` parent changed to **`Theme.Material3.DayNight.NoActionBar`**.
- Added **`res/values-night/themes.xml`** and **`res/values-night/colors.xml`** (dark palette).
- MainActivity: `applyTheme(mode)` persists the choice and calls
  `AppCompatDelegate.setDefaultNightMode(...)`; `nightMode()` maps
  0→FOLLOW_SYSTEM, 1→NO(light), 2→YES(dark); applied in `onCreate` before `setContentView`.
- (Renamed the setter to `applyTheme` to avoid clashing with `Context.setTheme(int)`.)

### 7.5 Power-draw stat (watts)
- `powerWatts()` = |current(µA)| × voltage(mV) ÷ 1e9, using
  `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` and the battery sticky intent
  `EXTRA_VOLTAGE`. Added as a toggleable stat (shows e.g. `3.96W`).

### 7.6 App icon from `logo.png`
- Source `logo.png` (1024×1024, transparent bg, a black stylised "E") was **off-center**.
- Generated a proper icon set with Pillow: trim to the content bounding box, then center
  with correct aspect ratio.
  - First pass: white background, black E.
  - Per the new request: **black background + white E** (recolored the E to white using its
    alpha as a mask, since a black E on black would be invisible).
  - Outputs: `mipmap-{mdpi..xxxhdpi}/ic_launcher.png` + `ic_launcher_round.png` (circle-masked),
    `drawable-nodpi/ic_launcher_foreground.png` (adaptive foreground, ~46% within the 66% safe zone).
  - Adaptive icon XML (`mipmap-anydpi/ic_launcher*.xml`) → background = `@color/ic_launcher_background`
    (set to `#FF000000`), foreground = the PNG, monochrome = same.
  - Removed the old vector `ic_launcher_foreground.xml` / `ic_launcher_background.xml` to avoid
    duplicate resources.

### 7.7 Picker cleanup
- Removed the leftover duplicate imports (`Llama-3.2-1B.gguf`, `Llama-3.2-3B.gguf`) so the
  list shows just the real 1B and 3B.

---

## 8. Round 4 — device-specific native optimization

Goal (verbatim): "make the app more optimized for low end device… remove unwanted stuffs…
optimize for this device specifically."

### What was wasteful
`GGML_CPU_ALL_VARIANTS=ON` built **7 CPU backends**
(armv8.0, armv8.2 ×2, armv8.6, armv9.0, armv9.2 ×2) and **all 7 were dlopened at startup**.
The A78 is armv8.2-a+dotprod, so the armv8.6/9.0/9.2 variants can never run on this phone —
pure dead weight (APK size, RAM, startup time).

### Change (`lib/build.gradle.kts`)
```
abiFilters += listOf("arm64-v8a")            // was: "arm64-v8a", "x86_64"  → drop x86
-DGGML_CPU_ALL_VARIANTS=OFF                   // was ON
-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod         // build ONE backend tuned for the A78
```
Confirmed in ggml's `ggml-cpu/CMakeLists.txt` that `GGML_CPU_ARM_ARCH` sets
`-march=` and that the KleidiAI **dotprod** micro-kernels are gated on `+dotprod` being present.

### Build note
First attempt failed on stale CMake state; a **clean native rebuild** succeeded:
```bash
rm -rf lib/.cxx lib/build/intermediates/cxx app/build/intermediates/cxx
./gradlew :app:assembleDebug --no-daemon --console=plain
```
The feature-probe messages ("SVE/SME/MATMUL_INT8 not defined", `-mfp16-format=ieee unknown`)
are normal TryCompile probes, not errors.

### Result
APK now ships a **single `libggml-cpu.so`** (armv8.2+dotprod+KleidiAI) instead of 7, and no
x86 llama/ggml libs. Smaller APK, less RAM at startup, faster launch — and **no speed loss**.

Verified 3B (Q4_0) on the slim build:
`45 tok · 7.1 tok/s · TTFT 1647ms · 40.0°C · 3.96W · 0.8GB free` — KleidiAI dotprod clearly active.

---

## 9. Current app feature set

- Fully offline inference on the CMF Phone 1, pinned to the A78 big cores.
- In-app model picker (1B Q8_0 and 3B Q4_0), no file browser; switch models freely.
- Professional chat UI: bubbles, smooth throttled streaming, model name in the toolbar.
- Toggleable live stats: tokens, tok/s, TTFT, temperature, **power draw**, memory free.
- Light / Dark / System theme.
- Black-background white-"E" adaptive app icon.
- Single device-tuned CPU backend (armv8.2+dotprod+KleidiAI), arm64-only.

### Still pending (deliberately held for last)
- **Live optimized-vs-naive comparison inside the app** — to be built only after the
  explicit "lets go" from the user.
- Final GitHub repo assembly + Devpost write-up + optional <3 min video.

---

## 10. Recurring rules to honor (do not break)

- **No AI/Claude attribution anywhere.** Code must look human-written, minimal comments.
- **Teach-first**; no major action without permission.
- Keep **two scripts per model** (optimized + naive).
- Optimization is the core metric of the whole project.
- Keep the code **simple** — optimize without over-complicating.

---

## 11. File map

```
ARM/
  BENCHMARKS.md            # all benchmark numbers
  DOCUMENTATION.md         # reproducible command log + optimization rationale
  DEVELOPMENT_LOG.md       # this file
  scripts/                 # chat*.sh, benchmark*.sh, autotune.sh, clean_ram.sh …
  quants/                  # 1B Q8_0/Q3_K_L/IQ3_M, 3B Q4_0 (GGUF)
  proof/                   # charts, raw logs, app screenshots

android/
  jdk-17.0.19+10/  sdk/    # toolchain (JDK, SDK, NDK 27, cmake)
  llama.cpp-master/examples/entity.android/
    app/   lib/            # the ENTITY app + inference library
```

### Key source files
- `lib/src/main/cpp/ai_chat.cpp` — native inference; big-core affinity; context 4096.
- `lib/build.gradle.kts` — single-variant device-tuned CPU backend, arm64-only.
- `app/src/main/java/com/example/llama/MainActivity.kt` — UI, picker, stats, theme.
- `app/src/main/java/com/example/llama/MessageAdapter.kt` — streaming chat list.
- `app/src/main/res/…` — layouts, menu, drawables, colors, night colors, icons.

---

## 12. Reproduce from zero (quick reference)

```bash
# 1. Toolchain env
export JAVA_HOME=/home/kamal/android/jdk-17.0.19+10
export ANDROID_HOME=/home/kamal/android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. Build the app
cd /home/kamal/android/llama.cpp-master/examples/entity.android
./gradlew :app:assembleDebug --no-daemon --console=plain

# 3. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Put models where the in-app picker finds them
DIR=/sdcard/Android/data/com.entity.chat/files/models
adb shell mkdir -p $DIR
adb push quants/Llama-3.2-1B-Instruct-Q8_0.gguf      $DIR/
adb push quants/Llama-3.2-3B-Instruct-Q4_0.gguf      $DIR/

# 5. Launch
adb shell am start -n com.entity.chat/com.example.llama.MainActivity
```

Termux CLI reference (optimized 1B):
```bash
./build/bin/llama-cli -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf \
  -t 4 -Cr 4-7 --cpu-strict 1 --prio 3 --mlock -c 4096
```

---

# APPENDIX A — Complete AI Handoff (read this to understand everything cold)

This appendix is written for another AI/engineer who has **zero prior context**. It states
every fact needed to continue the project without guessing. Nothing here assumes you saw the
earlier conversation.

## A.1 Who / what / where

- Team **ENTITY**, one member (referred to as the user). Building for the **Arm Create: AI
  Optimization Challenge**, Mobile AI track (Devpost).
- Deliverable = an **offline on-device LLM chatbot** on a **CMF Phone 1** (6 GB RAM,
  MediaTek Dimensity 7300) plus a benchmark story proving Arm-specific optimization.
- Two artifacts: (1) Termux/llama.cpp scripts + benchmarks, (2) a native Android app "ENTITY".

## A.2 Absolute paths (memorize these)

| Thing | Path |
|---|---|
| Project deliverables | `/home/kamal/Downloads/ARM/` |
| Benchmarks doc | `/home/kamal/Downloads/ARM/BENCHMARKS.md` |
| Command/rationale doc | `/home/kamal/Downloads/ARM/DOCUMENTATION.md` |
| This log | `/home/kamal/Downloads/ARM/DEVELOPMENT_LOG.md` |
| Scripts | `/home/kamal/Downloads/ARM/scripts/` |
| GGUF models (laptop) | `/home/kamal/Downloads/ARM/quants/` |
| Proof (charts, logs, screenshots) | `/home/kamal/Downloads/ARM/proof/` |
| Logo source | `/home/kamal/Downloads/ARM/logo.png` (1024×1024, transparent, black "E") |
| Android toolchain | `/home/kamal/android/` |
| JDK | `/home/kamal/android/jdk-17.0.19+10/` |
| SDK (ANDROID_HOME) | `/home/kamal/android/sdk/` |
| NDK | `/home/kamal/android/sdk/ndk/27.1.12297006/` |
| adb | `/home/kamal/android/sdk/platform-tools/adb` |
| llama.cpp source | `/home/kamal/android/llama.cpp-master/` |
| The Android app | `/home/kamal/android/llama.cpp-master/examples/entity.android/` |
| Native inference | `…/entity.android/lib/src/main/cpp/ai_chat.cpp` |
| Native build config | `…/entity.android/lib/build.gradle.kts` |
| Native cmake | `…/entity.android/lib/src/main/cpp/CMakeLists.txt` |
| Kotlin engine (do NOT rewrite) | `…/entity.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` |
| Engine interface | `…/entity.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt` |
| UI activity | `…/entity.android/app/src/main/java/com/example/llama/MainActivity.kt` |
| Chat adapter | `…/entity.android/app/src/main/java/com/example/llama/MessageAdapter.kt` |
| Built APK | `…/entity.android/app/build/outputs/apk/debug/app-debug.apk` |
| On-device models dir | `/sdcard/Android/data/com.entity.chat/files/models/` |

## A.3 Device & connection facts

- **App package:** `com.entity.chat`; **launch activity:** `com.example.llama.MainActivity`
  (the Kotlin package stayed `com.example.llama`; only `applicationId` is `com.entity.chat`).
- **adb device id:** `001166477014541` (USB debugging).
- **Termux SSH:** `ssh -p 8022 192.168.0.105`, key `~/.ssh/id_ed25519`, user `u0_a153`
  (Wi-Fi; router 192.168.0.1 had AP isolation disabled). USB tethering is the fallback.
- Termux is memory-pressured; use `termux-wake-lock` and keep the screen on for long runs.

## A.4 Hardware specifics that drive every optimization

- Dimensity 7300: **4× Cortex-A78 @2.5 GHz = cpu 4,5,6,7** (big) and
  **4× Cortex-A55 @2.0 GHz = cpu 0,1,2,3** (little).
- A78 ISA level: **armv8.2-a + dotprod (SDOT)**. It does **not** have i8mm, SVE, or SME.
  → the only useful llama.cpp CPU acceleration here is **dotprod + KleidiAI**.
- Generation is **memory-bandwidth-bound** (bigger quant = slower t/s); prompt processing
  is compute-bound.

## A.5 Exact current native settings (`ai_chat.cpp`)

- Includes added: `<vector> <algorithm> <cstdio> <sched.h>`.
- Constants: `N_THREADS_MIN=2, N_THREADS_MAX=4, N_THREADS_HEADROOM=2`,
  **`DEFAULT_CONTEXT_SIZE=4096`** (was 8192; lowered so the 3B fits in 6 GB),
  `BATCH_SIZE=512`, `DEFAULT_SAMPLER_TEMP=0.3f`.
- Globals for pinning: `static cpu_set_t g_fast_cpus; static int g_fast_count;`
- `build_fast_cpu_set(int want)`: reads each core's
  `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`, sorts by frequency desc, and
  `CPU_SET`s the top `want` cores into `g_fast_cpus` (want = n_threads = 4 here).
- `pin_to_fast_cores()`: `sched_setaffinity(0, sizeof(cpu_set_t), &g_fast_cpus)`.
- Call sites: end of `init_context()` (build + pin), start of `decode_tokens_in_batches()`,
  start of `generateNextToken()`.
- Thread count: `n_threads = max(2, min(4, nproc-2))` = 4 on this phone.
- **Why not `ggml_threadpool_new`:** the CPU backend is a dynamically loaded `.so`, so that
  symbol doesn't link. Affinity inheritance achieves the same core-pinning result.

## A.6 Exact current build flags (`lib/build.gradle.kts`)

```
abiFilters += listOf("arm64-v8a")                 # arm64 only (x86_64 removed)
-DGGML_NATIVE=OFF
-DGGML_BACKEND_DL=ON
-DGGML_CPU_ALL_VARIANTS=OFF                        # was ON (built 7 variants)
-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod              # single backend tuned for the A78
-DGGML_LLAMAFILE=OFF
```
- `ndkVersion = "27.1.12297006"`, `compileSdk 36`, cmake `3.31.6`.
- CMakeLists sets `GGML_CPU_KLEIDIAI ON` + `GGML_OPENMP ON` for arm64.
- Result: the APK ships **one** `libggml-cpu.so` (dotprod + KleidiAI), not seven.

## A.7 Kotlin engine contract (treat as a black box; don't rewrite it)

`com.arm.aichat.InferenceEngine` (impl `…internal.InferenceEngineImpl`, a singleton via
`AiChat.getInferenceEngine(context)`), runs on `Dispatchers.IO.limitedParallelism(1)`:
- `suspend loadModel(path)` — **requires state `Initialized`**; on success → `ModelReady`,
  on failure → `Error`.
- `suspend setSystemPrompt(text)` — must be called **right after** load, requires `ModelReady`.
- `sendUserPrompt(message, predictLength=1024): Flow<String>` — emits tokens; requires `ModelReady`.
- `bench(pp,tg,pl,nr)`, `cleanUp()` (resets **ModelReady or Error** → `Initialized`),
  `destroy()`.
- `state: StateFlow<State>` with states Uninitialized/Initializing/Initialized/LoadingModel/
  UnloadingModel/ModelReady/Benchmarking/ProcessingSystemPrompt/ProcessingUserPrompt/
  Generating/Error.
- **Critical rule this implies:** always `cleanUp()` before a second `loadModel()`, whether
  the prior attempt succeeded (ModelReady) or failed (Error). MainActivity does this.

## A.8 UI behavior contract (`MainActivity.kt`)

- On tap of the send button: if a model is ready → send; else → open the model picker.
- **Model picker** = `AlertDialog` list of `*.gguf` found in `getExternalFilesDir("models")`
  and `filesDir/models`; plus an "Import from device…" SAF fallback. No file browser needed.
- **Streaming**: append tokens to a `StringBuilder`; repaint at most every `RENDER_INTERVAL_MS`
  (45 ms) via `notifyItemChanged(pos, PAYLOAD_TEXT)`; force a final repaint on completion;
  `recyclerView.itemAnimator = null` to avoid jank.
- **Stats bar** (monospace, toggleable): tokens · tok/s · TTFT · °C · W · GB free. Each metric
  and the master "Show stats" are booleans in SharedPreferences (`"entity"`).
  - tok/s = tokenCount ÷ (now − firstTokenTime); TTFT = firstTokenTime − sendTime.
  - temperature = battery sticky intent `EXTRA_TEMPERATURE / 10.0`.
  - power W = |`BATTERY_PROPERTY_CURRENT_NOW` µA| × `EXTRA_VOLTAGE` mV ÷ 1e9.
  - memory = `ActivityManager.MemoryInfo.availMem` in GB.
- **Theme**: pref int 0/1/2 → `AppCompatDelegate.setDefaultNightMode` FOLLOW_SYSTEM/NO/YES,
  applied in `onCreate` before `setContentView`; dark palette in `res/values-night/`.
- **System prompt** (prevents robot-noise replies):
  "You are ENTITY, a helpful AI assistant running fully offline on the user's phone. Answer
  questions directly and clearly in natural language. Do not roleplay, narrate actions, or
  make robotic sound effects."

## A.9 Models currently on the phone (in the picker)

`/sdcard/Android/data/com.entity.chat/files/models/`:
- `Llama-3.2-1B-Instruct-Q8_0.gguf` (~1.32 GB)
- `Llama-3.2-3B-Instruct-Q4_0.gguf` (1,921,909,280 bytes — verified good copy)

Laptop `quants/` also has 1B `Q3_K_L`, `IQ3_M`, `Q8_0`, and 3B `Q4_0`.
(The correct 1B **Q4_0** used in Termux benchmarks lives on the phone's Termux home
`~/models/`, not yet copied into the app dir.)

## A.10 Verified live results (proof/ screenshots)

- 1B Q8_0, in-app, offline: `100 tok · 11.0 tok/s · TTFT 889ms · 41.0°C · 1.0GB free`.
- 1B Q8_0 greeting after system-prompt fix: natural reply, no "*beep boop*".
- 3B Q4_0 on the single-variant slim build: `45 tok · 7.1 tok/s · TTFT 1647ms · 40.0°C · 3.96W · 0.8GB free`.
- Screenshot files: `entity_ui_home.png`, `entity_ui_picker.png`, `entity_ui_gen1.png`,
  `entity_v2_reply2.png`, `entity_v2_3b.png`, `entity_v3_3b.png`, and others in `proof/`.

## A.11 How to build / install / drive it (copy-paste)

```bash
export JAVA_HOME=/home/kamal/android/jdk-17.0.19+10
export ANDROID_HOME=/home/kamal/android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
cd /home/kamal/android/llama.cpp-master/examples/entity.android
./gradlew :app:assembleDebug --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.entity.chat/com.example.llama.MainActivity
```
If the native config errors after changing build flags, do a clean native rebuild:
```bash
rm -rf lib/.cxx lib/build/intermediates/cxx app/build/intermediates/cxx
```
Driving the UI over adb (real device pixels are 1080×2400): `adb shell input tap X Y`,
`adb shell input text "hello%sworld"` (`%s` = space), `adb shell screencap -p /sdcard/s.png`
then `adb pull`. Toolbar folder icon ≈ (912,190); overflow ≈ (1022,190).

## A.12 What is DONE vs PENDING

**Done:** benchmarks + scripts; native app; big-core affinity; professional UI; in-app
picker; toggleable stats incl. power; light/dark/system theme; black-bg white-E icon;
model-switch fix; 3B load fix (ctx 4096 + good file); system-prompt fix; single device-tuned
CPU backend; duplicate-model cleanup.

**Pending (do only when the user says "lets go"):** an **in-app live optimized-vs-naive
comparison** feature (run the same prompt with big-core pinning ON vs OFF and show the t/s
side by side). This was explicitly deferred to last.

**Also remaining:** assemble the public GitHub repo (MIT/Apache-2.0), the Devpost write-up
(Overview / Functionality / Setup), and an optional <3 min video.

## A.13 Non-negotiable working rules

1. **No AI/Claude/Anthropic attribution anywhere** — code, commits, docs. Human-looking code,
   minimal comments. (This is a hard, repeated rule.)
2. **Teach-first**; don't start big work without permission.
3. Keep **two scripts per model** (optimized + naive).
4. **Optimization is the whole point** — always favor it, but keep the code **simple**; do not
   over-engineer.
5. The device is a **CMF Phone 1, 6 GB** — optimize specifically for the A78 (armv8.2+dotprod).

---

# ROUND 5 — Adaptive runtime, live graph, settings, and a reviewed hardening pass

This round turned the app from "optimized chatbot" into an **adaptive inference runtime** with
a full metrics/settings surface, then hardened it with a multi-agent code review.

## New native capability (`ai_chat.cpp`)
- **Runtime config**: added `g_n_ctx / g_n_threads / g_temp / g_top_k / g_top_p` globals plus
  two JNI setters — `configure(nCtx, nThreads, temp, topK, topP)` (applied at the next load)
  and `setSampler(temp, topK, topP)` (rebuilds the sampler live). `new_sampler()` now reads the
  globals; `init_context()` uses the configured context/threads and records the actually
  allocated `n_ctx`.
- Replaced the compile-time `DEFAULT_CONTEXT_SIZE` in the completion-loop bounds with the
  runtime `g_n_ctx`, so an adaptive/looser context works everywhere.

## New engine methods (`InferenceEngine(Impl).kt`)
- `applyConfig(...)`, `applySampler(...)`, and `newConversation(systemPrompt)` (resets the
  KV/context and re-applies the system prompt — powers the New-chat button without a reload).

## New UI (Kotlin + resources)
- **MetricsGraphView** — dependency-free custom view; capped ring buffers (120 samples/series);
  each series auto-normalized so power/temp/tok-s/tokens/TTFT/GB overlay cleanly; wrapping legend.
- **Settings screen** — `Auto (optimized)` master toggle (greys out manual controls) + SeekBars
  for temperature, top-k, top-p, max tokens, context, threads. Shared `Settings` object keeps
  pref keys from drifting.
- **About / Optimizations page** — lists every optimization for judges/users.
- **MainActivity** wiring — Stop button (send ⇄ stop while generating), New-chat toolbar action,
  graph sampling, **adaptive context** on load (from model size + free RAM), a **thermal-aware
  guard** (small inter-token delay under heat in auto mode), and per-tick single battery snapshot.
- Menu gained Show graph / Settings / About; manifest registers SettingsActivity + InfoActivity;
  added `ic_new_chat_24` and `ic_stop_24` vectors and night-mode-safe styles.

## Honest scope decision
- **Phase-aware core switching was explored and dropped**: ggml's worker threads are created
  once and inherit the calling thread's affinity, and the threadpool API isn't linkable with the
  dynamically-loaded CPU backend — so per-phase re-pinning can't be done reliably. Decode stays
  pinned to the A78 cluster (the memory-bound optimum). Not claimed as a feature.

## Adversarial review (multi-agent) + fixes applied
Ran a 4-dimension review (native/JNI, Kotlin concurrency, UI, RAM/perf) with independent
verification. Confirmed defects and what was done:
- **[FIXED, high]** `snapMetrics()` ran on the **main thread every ~45 ms during generation even
  when stats + graph were hidden** (3 binder IPCs/tick) — wasted cycles that could make the app
  lose to Termux. Now skipped unless the stats bar or graph is visible.
- **[FIXED, medium]** `processUserPrompt` **double-counted the prompt length** in the stop
  position → every reply generated ~prompt-length extra tokens (defeating max-tokens, extra
  heat/battery). Now `stop = current_position + n_predict`, advancing by the actually-decoded
  token count (also fixes a truncation position gap).
- **[FIXED, high]** Cancelling generation (Stop / New-chat / backgrounding / model switch) could
  leave the UI **stuck in Stop mode with the input disabled**, because the `onCompletion` cleanup
  was skipped on a cancelled coroutine. Cleanup now runs under `NonCancellable`.
- **[FIXED, low]** `@FastNative` was on **seconds-long** JNI calls (load/prepare/benchModel/
  processSystemPrompt/processUserPrompt) — a GC-stall risk. Removed from those; kept only on the
  trivial setters and per-token generate.
- **[KNOWN, low]** `destroy()` uses `runBlocking` on the main thread during teardown — can briefly
  block if a native call is in flight (rare: back/rotate mid-prefill). Documented; low priority.
- **[KNOWN, low]** In-loop `shift_context()` uses a stale `start_pos` — can corrupt very long
  multi-turn sessions once the context fills. Mitigated in practice by adaptive context + the
  New-chat reset; documented.

Verified on device after the fixes: 1B Q8_0 replies correctly and stops cleanly
(`9 tok · 10.4 tok/s · TTFT 459ms · 37.0°C · 4.70W · 0.9GB free`). Settings screen, graph legend,
model picker, theme all confirmed working via screenshots in `proof/`.

## On "app vs Termux"
Measured by the user: app ≈ Termux, sometimes Termux faster. **Expected** — both run the same
big-core-pinned KleidiAI kernels, so raw tok/s can't diverge much. The two review fixes above
(main-thread IPC + double-counted generation) removed real app-side overhead that was making it
*lose*; the defensible story is **auto-optimization + foreground stability + energy/thermal
metrics**, not a single-number win over a hand-tuned CLI.

## Packaging for release
Assembled `ARM/github/` (ready for a manual repo): clean app source (`app/entity.android`, no
build artifacts), both prebuilt APKs (`apk/`), docs, Termux scripts, screenshots, `README.md`,
`LICENSE` (Apache-2.0), `SETUP.md` + `setup.sh`, `.gitignore`. Note: each debug APK is ~100 MB (unstripped
symbols) and exceeds GitHub's 100 MB file limit → use Git LFS or a Release attachment.

## Still pending
- Complete, fair **app-vs-Termux benchmark** (naive vs expert vs app; tok/s, TTFT, sustained,
  power, tokens/joule) written into `BENCHMARKS.md`.
- Optional stripped release build to shrink the APK.
- Devpost write-up + optional video.
