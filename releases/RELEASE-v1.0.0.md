# ENTITY v1.0.0 — 2026-07-02

**APK:** `ENTITY-optimized-single-variant-20260702-2335.apk`

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

## Added
- **Big-core affinity** — inference pinned to the Cortex-A78 performance cluster via
  `sched_setaffinity`, with cores chosen by live max frequency (keeps work off the slow A55 cores).
- **Device-tuned CPU backend** — a single `armv8.2-a + dotprod + KleidiAI` build instead of the seven
  generic CPU variants; **arm64-v8a only** (x86 removed) for a smaller, faster, lighter app.
- **Adaptive context** — sizes the context window from model size and free RAM: a 3B-class model gets a
  4096-token window above 2.2 GB free and a 2048-token window at or below it.
- **Professional chat UI** — in-app model picker, smooth token streaming, toggleable stats (tokens,
  tok/s, TTFT, temperature, **power draw in watts**, free memory), and light/dark/system themes.
- Black-background white-"E" app icon.

## Fixed
- 3B "error reading file" (KV OOM + a bad on-device copy).
- Model switching reliability.
- Robotic "*beep*boop*" replies (via a strict system prompt).
