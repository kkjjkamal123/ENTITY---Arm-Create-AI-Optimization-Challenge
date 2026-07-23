# Apps

Two Android apps, both arm64-v8a, both fully offline at inference time.

| Folder | App | Package | Current |
|---|---|---|---|
| [`entity.android/`](entity.android/) | **ENTITY** - the chat app | `com.entity.chat` | v3.6.0 |
| [`entity.bench.android/`](entity.bench.android/) | **ENTITY Bench** - the measurement app | `com.entity.bench` | v2.1.0 |

## Why two apps

They are not the same app with a flag. The chat app is the product; the bench app is the
instrument, and separating them is what keeps the numbers honest:

- The bench app has **no chat surface at all**, so a benchmark cannot be perturbed by UI work
  competing with the decode threads - a real regression the chat app hit once (v3.0.1).
- It carries arms the shipped app must never run: an efficiency arm pinned to the LITTLE cluster,
  a naive 8-thread baseline, a thread sweep, and an `adpf` arm.
- It can upload results to a public dataset. The chat app never touches the network for
  measurement.

The rule that binds them: **the bench app's `optimized` arm must be exactly what the chat app
ships.** Both trees carry their own `lib/src/main/cpp/ai_chat.cpp`, and any change to the inference
path has to land in both, or the benchmark starts measuring a configuration that no longer exists.

## Shared structure

Both follow the same layout:

```
app/     Kotlin UI, activities, settings, benchmark plumbing
lib/     the inference library
  src/main/cpp/ai_chat.cpp   JNI + llama.cpp + core selection + thread pools
  src/main/java/com/arm/aichat/   the Kotlin engine interface
```

`lib/` is a reusable Android library - see the FAQ entry on reusing the Arm runtime logic.

## Building

Neither app builds standalone: their CMake reaches up into a llama.cpp checkout. See
[`../docs/BUILD.md`](../docs/BUILD.md).

## Where to read next

- [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) - module layout and file-by-file inventory
- [`../docs/OPTIMIZATIONS.md`](../docs/OPTIMIZATIONS.md) - what the native layer actually does
