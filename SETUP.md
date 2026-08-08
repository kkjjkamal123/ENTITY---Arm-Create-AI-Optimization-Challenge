# Building ENTITY

> For the full walkthrough (toolchain table, validation steps, adapting the build to a different
> Arm SoC, troubleshooting) see **[`docs/BUILD.md`](docs/BUILD.md)**. This file is the short version.

The app is the `entity.android` Gradle project. Its native library builds **against the
upstream llama.cpp source** (its CMake does `add_subdirectory` into the llama.cpp root six
levels up), so the app must sit inside a llama.cpp checkout at `examples/entity.android/`.

## 1. Lay out the source

```bash
# fetch llama.cpp (master), then drop the app into its examples/
curl -sL -o llama.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/heads/master.tar.gz
tar xzf llama.tar.gz            # -> llama.cpp-master/
cp -r app/entity.android llama.cpp-master/examples/entity.android
```

(`setup.sh` in this folder does exactly this.)

## 2. Toolchain

- **JDK 17**
- **Android SDK** with:
  - platform-tools (adb)
  - platform android-35 or android-36
  - build-tools 35+
  - **NDK 27.1.12297006**
  - **CMake 3.22.1**

Point Gradle at the SDK (either export `ANDROID_HOME`, or create
`llama.cpp-master/examples/entity.android/local.properties` with `sdk.dir=/path/to/Android/sdk`).

## 3. Release signing (optional)

To sign a release APK with a real release keystore instead of the debug config:

```bash
# Generate a keystore (one time only)
keytool -genkeypair -v -keystore entity-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias entity
```

Create `llama.cpp-master/examples/entity.android/keystore.properties` (gitignored):
```
storeFile=/path/to/entity-release.jks
storePassword=your-store-password
keyAlias=entity
keyPassword=your-key-password
```

If `keystore.properties` is absent, the release build silently falls back to debug signing, so
contributors are never blocked. The release build will only use the keystore if the file exists.

## 4. Build

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd llama.cpp-master/examples/entity.android
./gradlew :app:assembleDebug --no-daemon --console=plain
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

For a release build:
```bash
./gradlew :app:assembleRelease --no-daemon --console=plain
# APK -> app/build/outputs/apk/release/app-release.apk
```

The build ships **7 Arm CPU backend variants** with runtime dispatch (see `lib/build.gradle.kts`:
`GGML_CPU_ALL_VARIANTS=ON`). To customize for a single target SoC instead, set
`GGML_CPU_ALL_VARIANTS=OFF` and `GGML_CPU_ARM_ARCH=<target>` (e.g. `armv8.2-a+dotprod` for
Dimensity 7300). See `docs/BUILD.md` for details.

## 5. Install + add models

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# put GGUF models where the in-app picker finds them (no permission needed):
DIR=/sdcard/Android/data/com.entity.chat/files/models
adb shell mkdir -p $DIR
adb push Llama-3.2-1B-Instruct-Q4_0.gguf $DIR/
adb push Llama-3.2-3B-Instruct-Q4_0.gguf $DIR/

adb shell am start -n com.entity.chat/com.example.llama.MainActivity
```

Open the app, tap the model line in the header, import a model, and chat — fully offline.

## Notes

- The debug APK is large (~50 MB) because native symbols aren't stripped and 7 CPU backend variants
  are included. A release build v2.0.0 (`./gradlew :app:assembleRelease` — R8 + resource shrinking
  + stripped native symbols) shrinks to ~9.8 MB (up from ~7 MB in v1.7.0; the growth is due to
  shipping 7 Arm CPU variants instead of one for universal Arm support). Release signing uses a
  dedicated keystore (see **Release signing** above) with fallback to debug signing if
  `keystore.properties` is absent; see `docs/BUILD.md` for more detail.
- Models are **not** included in this repo. Download any Llama-3.2 GGUF (e.g. from Hugging
  Face) — Q4_0 is recommended on this CPU.
