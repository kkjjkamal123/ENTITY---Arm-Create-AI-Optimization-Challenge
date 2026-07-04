# ENTITY — an adaptive on-device LLM runtime for Arm phones

**Team ENTITY · Arm Create: AI Optimization Challenge (Mobile AI)**

 It is not "a chatbot that uses llama.cpp" — it's a small **inference
optimization layer**, proven on real consumer hardware (a **CMF Phone 1**, MediaTek
Dimensity 7300, 6 GB RAM, Cortex-A78 + A55 big.LITTLE).

> Drop in any runnable GGUF model, and ENTITY profiles the device and configures itself to
> run it as fast as the phone allows — offline, with live speed/energy/thermal metrics.

---

## What makes it more than a demo

| Optimization | What it does |
|---|---|
| **Big-core affinity** | Pins inference to the Cortex-A78 performance cluster via `sched_setaffinity`, choosing cores by live `cpufreq` ranking. Keeps work off the slow A55 cores — the reason a naive Android app loses to a tuned CLI. |
| **Device-tuned CPU backend** | A **single** `armv8.2-a + dotprod` build with Arm **KleidiAI** kernels, instead of the 7 generic CPU variants most builds ship. Smaller APK, less RAM at startup, faster launch. arm64-only. |
| **Adaptive context** | Context window is chosen from **model size + free RAM**, so any runnable model fits without OOM (a 1B gets a big window; a 3B is trimmed to fit 6 GB). |
| **Auto (optimized) mode** | One switch applies all of the above automatically. Turn it off to tune temperature, top-k, top-p, max tokens, context and threads by hand. |
| **Thermal-aware guard** | Eases off between tokens under sustained heat to hold a steadier throughput instead of hard-throttling. |
| **Live metrics** | tokens, tok/s, TTFT, temperature, **power draw (W)**, free memory — as a stats bar and an overlayable, per-series-toggleable **graph**. |

Honest framing: against a **hand-tuned Termux CLI** the raw tokens/sec are near-identical —
both use the same cores and the same KleidiAI kernels, so no app can beat that on one number.
ENTITY's value is delivering that optimization **automatically**, in the **foreground**, with
**energy/thermal awareness** and a real UI — and beating the **default** (un-tuned) experience
most users actually get.

---

## Features

- Fully offline chat (Llama 3.2 1B / 3B and any other runnable GGUF).
- In-app model picker (no file browser needed).
- Professional chat UI with smooth streaming, **Stop** and **New chat**.
- Settings screen with an **Auto (optimized)** master toggle.
- Live metrics bar + toggleable multi-series graph.
- Light / Dark / System theme.
- About page listing every optimization.

## Screenshots

| Chat + stats | Live graph | Settings |
|---|---|---|
| ![chat](screenshots/v5_reply.png) | ![graph](screenshots/v4_picker.png) | ![settings](screenshots/v4_settings2.png) |

---

## Build

The app is an Android Studio / Gradle project (Kotlin UI + C++ inference via the NDK).
It builds against the upstream **llama.cpp** source. See **[SETUP.md](SETUP.md)** for the
exact steps; in short:

```bash
# 1. get llama.cpp and drop this app into examples/
# 2. point local.properties at your Android SDK
# 3. build
cd llama.cpp/examples/entity.android
./gradlew :app:assembleDebug
```

Prebuilt debug APKs are in [`apk/`](apk/) (note: each is ~100 MB — see apk/README.md).

## Repository layout

```
app/entity.android/   the Android app (Kotlin UI module + native lib module)
apk/                  prebuilt debug APKs (optimized single-variant builds)
docs/                 BENCHMARKS.md, DOCUMENTATION.md, DEVELOPMENT_LOG.md
scripts/              Termux / llama.cpp benchmark + chat scripts (optimized + naive)
screenshots/          app screenshots
```

## Hardware target

MediaTek Dimensity 7300: 4× **Cortex-A78 @2.5 GHz** (cpu 4-7, `armv8.2-a + dotprod`) +
4× Cortex-A55 @2.0 GHz (cpu 0-3). No i8mm / SVE / SME — so dotprod + KleidiAI is the
right acceleration path, and Q4_0 (4-bit dotprod kernels) often beats a "smaller" 3-bit format.

## License

MIT — see [LICENSE](LICENSE). Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT)
and Arm [KleidiAI](https://gitlab.arm.com/kleidi/kleidiai) (Apache-2.0).
