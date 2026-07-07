# BUILD

Precise, reproducible build/run/validate steps for an Arm64 Android device. This supersedes
[`../SETUP.md`](../SETUP.md) with more detail; the two are kept consistent — if you only need the
short version, `SETUP.md` is a fine quick reference.

## Toolchain (exact versions this repo builds with)

| Tool | Version | Where it's pinned |
|---|---|---|
| JDK | 17 | `compileOptions` / `kotlin.jvmToolchain(17)` in both `build.gradle.kts` files |
| Android compileSdk / targetSdk | 36 | `app/build.gradle.kts`, `lib/build.gradle.kts` |
| minSdk | 33 | `app/build.gradle.kts` |
| Android Gradle Plugin | 8.13.2 | `gradle/libs.versions.toml` (`agp`) |
| Kotlin | 2.3.0 | `gradle/libs.versions.toml` (`kotlin`) |
| NDK | 27.1.12297006 | `lib/build.gradle.kts` (`ndkVersion`) |
| CMake | 3.31.6 | `lib/build.gradle.kts` (`externalNativeBuild.cmake.version`) |
| llama.cpp | upstream `master` | fetched separately, not vendored in this repo |

The native library targets **`arm64-v8a` only** (`abiFilters += listOf("arm64-v8a")` in
`lib/build.gradle.kts`) — there is no x86/x86_64 build output from this configuration as shipped.

## 1. Lay out the source

The native build's `CMakeLists.txt` does `add_subdirectory()` six directory levels up from
`lib/src/main/cpp/`, so the app **must** sit inside a llama.cpp checkout at
`examples/entity.android/` — it cannot build standalone.

```bash
curl -sL -o llama.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
tar xzf llama.tar.gz                                   # -> llama.cpp-master/
cp -r app/entity.android llama.cpp-master/examples/entity.android
```

Or just run [`../setup.sh`](../setup.sh), which does exactly this.

## 2. Point Gradle at the SDK

Either export `ANDROID_HOME`, or create
`llama.cpp-master/examples/entity.android/local.properties` with:
```
sdk.dir=/path/to/Android/sdk
```

Make sure the SDK has: platform-tools, an Android 36 platform + build-tools, **NDK
27.1.12297006**, and **CMake 3.31.6** installed (`sdkmanager --install "ndk;27.1.12297006"
"cmake;3.31.6"`).

## 3. Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd llama.cpp-master/examples/entity.android
./gradlew :app:assembleDebug --no-daemon --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk  (~100 MB, symbols not stripped)
```

The first build compiles llama.cpp itself via CMake/NDK for `arm64-v8a` — expect several minutes.
Subsequent builds reuse the `.cxx/` CMake cache.

For a smaller, production-shaped artifact:
```bash
./gradlew :app:assembleRelease --no-daemon --console=plain
```
Both `debug` and `release` build types already set `isMinifyEnabled = true` /
`isShrinkResources = true` in `app/build.gradle.kts`; the debug APK stays large because native
`.so` debug symbols aren't stripped, not because Kotlin/resource shrinking is off.

## 4. Install and add a model

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Developer path: adb can write into the app's private storage directly.
DIR=/sdcard/Android/data/com.entity.chat/files/models
adb shell mkdir -p $DIR
adb push Llama-3.2-1B-Instruct-Q4_0.gguf $DIR/
adb push Llama-3.2-3B-Instruct-Q4_0.gguf $DIR/

adb shell am start -n com.entity.chat/com.example.llama.MainActivity
```

Models are not bundled with this repo. Q4_0 GGUF quantizations are recommended on this CPU (see
[`OPTIMIZATIONS.md`](OPTIMIZATIONS.md#4-quantization-q4_0-on-dotprod)) — e.g. from
[Hugging Face / bartowski](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF).

**End users don't use `adb push`.** The app's real model-loading path is the in-app **Import from
device** picker (⋮ → Select model → Import from device…), built on the Storage Access Framework —
it works from any storage location on any phone, without a computer or adb. `adb push` is a
developer shortcut that happens to work because `adb` can write to `Android/data/` directly.

## 5. Validate

- Open the app, load a model, send a message — confirm streaming works and stats show sane
  numbers (tok/s roughly matching the tables in `../benchmarks/BENCHMARKS.md` for your model size).
- ⋮ → **Benchmark** on the loaded model — confirms the optimized (4× A78) pass is faster than the
  naive (8-core) pass on your build. This is the fastest way to confirm a change to the native
  layer or affinity logic hasn't regressed the core optimization.
- `adb logcat -s AiChat:* ai-chat:*` while loading a model shows `init_context` logging the chosen
  thread count, context size, and `"pinned inference to %d fast cores"` — confirms affinity pinning
  actually ran (see `pin_to_fast_cores()` in `ai_chat.cpp`).

## Device-specific configuration: adapting `GGML_CPU_ARM_ARCH`

This build is tuned for the CMF Phone 1's Dimensity 7300 (Cortex-A78, `armv8.2-a`, has `dotprod`
a.k.a. `SDOT`, lacks `i8mm`/`SVE`/`SME`). The relevant flags live in `lib/build.gradle.kts`:

```kotlin
arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod"
```

and `lib/src/main/cpp/CMakeLists.txt` additionally turns on KleidiAI for `arm64-v8a`:

```cmake
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()
```

**Why single-variant, not `GGML_CPU_ALL_VARIANTS=ON`:** a normal llama.cpp Android build compiles
~7 CPU backend variants (covering different Arm feature sets) into the APK and dispatches to the
right one at runtime. That's portable but bloats APK size, startup RAM, and adds a runtime
dispatch indirection. Since this app targets one known SoC, compiling exactly the backend that SoC
needs removes all of that overhead.

**To target a different SoC:**
1. Identify its Arm architecture level and available extensions. On-device:
   ```bash
   cat /proc/cpuinfo | grep Features        # look for asimddp (dotprod), i8mm, sve, sme
   ```
   or check the SoC's public Cortex/Neoverse core spec sheet.
2. Change `GGML_CPU_ARM_ARCH` to match, e.g.:
   - `armv9-a+sme` — newer Armv9 cores with SME (e.g. Cortex-X4/X925-class).
   - `armv8.2-a+fp16` — older/other cores without `dotprod` (check first; forcing `+dotprod` on a
     core without `SDOT` will crash with `SIGILL` at the first matmul).
   - `armv8.6-a+i8mm+dotprod` — cores with `i8mm` (bigger KleidiAI win than dotprod-only).
3. If the target has heterogeneous big.LITTLE clusters, the affinity logic in `ai_chat.cpp`
   (`build_fast_cpu_set`) needs no changes — it ranks cores by live `cpufreq` at runtime, so it
   adapts automatically. Only the *compiled kernel set* is SoC-specific; the *core selection* is not.
4. For a portable/universal build instead of a device-specific one, set
   `GGML_CPU_ALL_VARIANTS=ON` and drop the `abiFilters` restriction if you also want x86_64 — at
   the cost of the size/startup benefits described in [`OPTIMIZATIONS.md`](OPTIMIZATIONS.md#2-single-device-tuned-cpu-backend).

## Common build issues

- **CMake `add_subdirectory` fails / "llama.cpp not found"** — the app isn't inside
  `examples/entity.android/` of a llama.cpp checkout. Re-run step 1.
- **`SIGILL` on first inference** — `GGML_CPU_ARM_ARCH` requests an instruction the physical CPU
  doesn't have (commonly `+dotprod` on a core without `SDOT`). Lower the arch flag.
- **NDK/CMake version mismatch errors** — install the exact pinned versions
  (`27.1.12297006` / `3.31.6`); newer versions usually work but aren't what this repo is tested
  against.
- **Gradle can't find the SDK** — set `local.properties` or `ANDROID_HOME` as in step 2.
