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
| C/C++ compiler | clang 18.0.2 (bundled with NDK 27.1) | determined by the NDK version; cross-compiles to `aarch64-linux-android` |
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

### Release build

```bash
./gradlew :app:assembleRelease --no-daemon --console=plain
# -> app/build/outputs/apk/release/app-release.apk (~9.8 MB)
```
The release build (versionName **2.0.0**) applies R8 minification + resource shrinking and ships
stripped native symbols. It is larger than v1.7.0 (~9.8 MB vs ~7 MB) because 7 Arm CPU backend
variants are now shipped instead of one, providing universal Arm support. It is signed with a release
keystore (see **Release keystore setup** below) — credentials are read from a gitignored
`keystore.properties` file. If the keystore file is absent, the build silently falls back to debug
signing, so contributors are never blocked. Both build types set `isMinifyEnabled = true` /
`isShrinkResources = true` in `app/build.gradle.kts`; the debug APK stays large (~49 MB) because it
deliberately keeps native `.so` debug symbols (`keepDebugSymbols` for `**/*.so`). The app module pins
`ndkVersion 27.1.12297006` so release symbol stripping works.

### Release keystore setup

The release APK is signed with a dedicated release keystore (separate from the debug keystore used
for development). The keystore lives **outside the repo** so it can be managed securely.

**Generate a keystore (one time only):**
```bash
keytool -genkeypair -v -keystore entity-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias entity
```

**Point the build at your keystore:**

Create `app/entity.android/keystore.properties` (this path is gitignored):
```
storeFile=/path/to/entity-release.jks
storePassword=your-store-password
keyAlias=entity
keyPassword=your-key-password
```

The build reads these four keys and uses them to sign the release APK. The
`signingConfigs.create("release")` block in `app/build.gradle.kts` looks for `keystore.properties`
at the app project root; if it doesn't exist, the release build silently falls back to the debug
config, so contributors can always build without a keystore.

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
device** picker (tap the model line in the header → Import from device…), built on the Storage Access Framework —
it works from any storage location on any phone, without a computer or adb. `adb push` is a
developer shortcut that happens to work because `adb` can write to `Android/data/` directly.

## 5. Validate

- Open the app, load a model, send a message — confirm streaming works and stats show sane
  numbers. The current reference in-app measurement is
  [`../benchmarks/BENCHMARKS.md`](../benchmarks/BENCHMARKS.md). The historical Termux raw output
  uses different workloads and CLI-only realtime priority.
- Menu drawer (≡) → **BENCHMARK** on the loaded model — runs three arms: the naïve eight-thread path,
  threads-only (Auto's thread count with affinity off), and ENTITY Auto, which ranks cores by
  maximum frequency and runs both inference phases on that fast-core set. This is the fastest way
  to confirm a native or affinity change has not regressed the shipped path.
- `adb logcat -s AiChat:* ai-chat:*` while loading a model shows `init_context` logging the chosen
  thread count, context size, and the affinity actually applied — confirms pinning ran (see
  `pin_to_fast_cores()` in `ai_chat.cpp`).
- `adb logcat -s ai-chat | grep "effective cpus"` during a benchmark prints the CPU mask **the
  kernel reports back** for each arm. A valid three-arm run shows the Auto arm on the fast cores
  only and the threads-only arm on every core. If those two print the same mask, the ablation did
  not happen and its attribution is meaningless — see
  [REPRODUCIBILITY.md](../benchmarks/REPRODUCIBILITY.md).

## Device-specific configuration: adapting CPU backend variants

The build ships **7 Arm CPU backend variants** (armv8.0 to armv9.2, each compiled with KleidiAI)
with runtime dispatch for arm64 Android phones. The relevant flag in `lib/build.gradle.kts`:

> **The backend variant is not the whole story.** KleidiAI registers matmul kernels for `Q4_0` and
> `Q8_0` only. Load a K-quant or IQ model and every matmul falls back to generic ggml no matter
> which variant was selected — the i8mm/dotprod path simply never runs. Measured on a Dimensity
> 7300, switching a 1B from Q3_K_L to Q4_0 took prompt throughput from 43 to 121 tok/s. See
> [OPTIMIZATIONS §4](OPTIMIZATIONS.md#4-quantization-is-what-gates-arms-kleidiai-kernels).

```kotlin
arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
```

At startup, ggml scores each variant against the physical CPU and `prepare()` selects the best one.
This makes the APK larger (~9.8 MB) but enables universal Arm support — no SIGILL on old cores, no
missed optimizations on new ones.

### The seven variants, exactly

These come from ggml's Android branch of `ggml/src/CMakeLists.txt` (`ggml_add_cpu_backend_variant`,
`CMAKE_SYSTEM_NAME MATCHES "Android"`). Each is the baseline Armv8-a NEON build plus the listed
optional features; ggml picks the highest one whose every feature the CPU reports.

| Variant | Optional ISA features | Typical cores |
|---|---|---|
| `android_armv8.0_1` | none (baseline NEON) | Cortex-A53/A72-era phones; the no-SIGILL fallback |
| `android_armv8.2_1` | dotprod | first dotprod cores without fp16 vector arithmetic |
| `android_armv8.2_2` | dotprod, fp16 | Cortex-A75 to A78 class — **what the reference Dimensity 7300 selects** |
| `android_armv8.6_1` | dotprod, fp16, i8mm | Armv8.6/early Armv9 big cores with `i8mm` (Cortex-A710 class) |
| `android_armv9.0_1` | dotprod, fp16, i8mm, SVE2 | Armv9.0 cores exposing SVE2 to userspace |
| `android_armv9.2_1` | dotprod, fp16, i8mm, SVE, SME | Armv9.2 cores with SME |
| `android_armv9.2_2` | dotprod, fp16, i8mm, SVE, SVE2, SME | newest Armv9.2 cores (Cortex-X925 class) |

To see which variant loaded on a given phone, watch logcat during model load — ggml logs the
chosen backend — or check `/proc/cpuinfo` `Features` (`asimddp` = dotprod, `asimdhp` = fp16,
`i8mm`, `sve`, `sve2`, `sme`) and read the table bottom-up.

and `lib/src/main/cpp/CMakeLists.txt` additionally turns on KleidiAI for `arm64-v8a`:

```cmake
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()
```

**To customize for a single backend (smaller APK, faster startup, trade-off: platform-specific):**

If you want to ship only one backend for a specific SoC (like v1.7.0 did), change `lib/build.gradle.kts`:

```kotlin
arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
arguments += "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod"  # (or your target architecture)
```

1. Identify the target SoC's Arm architecture level and available extensions. On-device:
   ```bash
   cat /proc/cpuinfo | grep Features        # look for asimddp (dotprod), i8mm, sve, sme
   ```
   or check the SoC's public Cortex/Neoverse core spec sheet.
2. Set `GGML_CPU_ARM_ARCH` to match, e.g.:
   - `armv9-a+sme` — newer Armv9 cores with SME (e.g. Cortex-X4/X925-class).
   - `armv8.2-a+fp16` — older/other cores without `dotprod` (check first; forcing `+dotprod` on a
     core without `SDOT` will crash with `SIGILL` at the first matmul).
   - `armv8.6-a+i8mm+dotprod` — cores with `i8mm` (bigger KleidiAI win than dotprod-only).
3. If the target has heterogeneous big.LITTLE clusters, the affinity logic in `ai_chat.cpp`
   (`build_fast_cpu_set`) needs no changes — it ranks cores by live `cpufreq` at runtime, so it
   adapts automatically. Only the *compiled kernel set* is SoC-specific; the *core selection* is not.

**Universal builds (v2.0.0 default):** The current `GGML_CPU_ALL_VARIANTS=ON` ships all 7 variants
for portable Arm support across old and new phones. This increases APK size (~9.8 MB vs ~7 MB
single-backend) and adds a runtime dispatch step, but eliminates SIGILL risks and opens newer ISA
optimizations without recompilation.

## Common build issues

- **CMake `add_subdirectory` fails / "llama.cpp not found"** — the app isn't inside
  `examples/entity.android/` of a llama.cpp checkout. Re-run step 1.
- **`SIGILL` on first inference** — `GGML_CPU_ARM_ARCH` requests an instruction the physical CPU
  doesn't have (commonly `+dotprod` on a core without `SDOT`). Lower the arch flag.
- **NDK/CMake version mismatch errors** — install the exact pinned versions
  (`27.1.12297006` / `3.31.6`); newer versions usually work but aren't what this repo is tested
  against.
- **Gradle can't find the SDK** — set `local.properties` or `ANDROID_HOME` as in step 2.
