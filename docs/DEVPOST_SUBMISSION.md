# Project ENTITY — Devpost submission (copy-paste ready)

Everything for the submission in one place: the project story, the judges'-form answers,
and the tagline. All numbers below match what we actually measured on the device.

---

## Tagline / short description

> An adaptive, fully-offline LLM runtime for Arm phones — it profiles the device and tunes
> itself to run any runnable GGUF model as fast as the hardware allows. Proven on a CMF Phone 1
> (Dimensity 7300, 6 GB), including a 3B model running with only ~2 GB of free RAM.

---

# About the project

## Inspiration

Project ENTITY started with a simple question: **can a normal Arm-based phone run a useful
private AI assistant fully offline if the software is designed for the hardware instead of
treated like a small desktop?**

Most on-device AI demos stop at "it runs." We wanted to go deeper and focus on what actually
matters on a real phone: speed, memory fit, thermal stability, battery impact, and a smooth
user experience. That led us to build ENTITY not just as a chat app, but as an **adaptive LLM
runtime for Arm phones**.

## What it does

ENTITY is a fully offline AI assistant for Android that runs GGUF language models directly on
the phone.

It is designed around the realities of mobile Arm hardware:
- big.LITTLE CPU topology
- limited RAM
- thermal limits
- battery constraints

The app includes:
- on-device model loading with an **in-app model picker** (no file browser)
- a smooth streaming chat UI with **Stop** and **New chat**
- **live runtime metrics**: tokens, tokens/sec, TTFT, temperature, power draw, free memory
- an **Auto (optimized)** mode plus a manual settings layer (temperature, top-k, top-p,
  max tokens, context, threads)
- a metrics **graph** and light/dark/system themes

## How we built it

We built ENTITY on top of **llama.cpp**, then turned it into a native Android app using
**Kotlin** for the UI and **C++** for the inference path.

Our target device was the **CMF Phone 1** with a MediaTek **Dimensity 7300**:
- 4× Cortex-A78 performance cores (`armv8.2-a + dotprod`)
- 4× Cortex-A55 efficiency cores
- 6 GB RAM

A major part of the work was making the runtime **Arm-aware**:
- pinning inference to the **Cortex-A78 performance cluster** (picking cores by live CPU
  frequency), so work stays off the slow A55 cores
- building a **device-tuned CPU backend** for `armv8.2-a + dotprod` with Arm **KleidiAI**
  kernels — a single backend instead of the seven generic CPU variants most builds ship
- **adaptive context**: sizing the context window from the model size and free RAM so larger
  models still fit on a 6 GB phone
- reducing app-side overhead so the runtime, not the UI, drives the phone

To measure efficiency, we tracked battery current and voltage and estimated power draw with:

$$
P = \frac{|I_{\mu A}| \times V_{mV}}{10^9}
$$

and then used:

$$
\text{tokens per watt} = \frac{\text{tokens/sec}}{P}
$$

so we could treat mobile AI as an **efficiency problem**, not just a raw-speed problem.

## Challenges we ran into

The hardest part was that mobile AI optimization is a **systems problem**, not just a model
problem.

**1. The app was initially slower than the command line.** At first the Android app performed
worse than a tuned Termux setup with the same model. The cause was runtime behavior on Android —
thread scheduling, core placement, and per-token UI/measurement overhead — not the model. Fixing
it meant pinning threads to the performance cores and cutting main-thread work during generation.

**2. Fitting a 3B model into very little free RAM.** Although the phone has 6 GB total, in real
use only about **1.5–2 GB was actually free** at runtime. Getting a **3B model to load and
generate stably within that budget**, while keeping a **4096-token context/KV window**, took
real tuning: llama.cpp **memory-maps the weights** (so the model isn't fully resident and pages
are reclaimable under pressure), and our **adaptive context** trimmed the window to fit. Under
load, free memory dropped to around **0.8 GB** and the model still ran.

**3. Keeping the UI smooth during generation.** Updating the interface on every token caused
visible jank. We throttled rendering and used lighter partial updates so the app stayed
responsive while streaming.

**4. Learning which optimizations actually matter on Arm.** Not every idea helped. Some sounded
useful but gave little benefit on this specific device. The best optimization depends heavily on
CPU topology, memory bandwidth, quantization format, and thermal behavior over time.

## Accomplishments that we're proud of

- A **fully offline** Android LLM experience on real consumer Arm hardware.
- An **Arm-aware runtime** tuned for a big.LITTLE mobile CPU (performance-core pinning +
  KleidiAI/dotprod).
- Running a **3B offline LLM with only ~2 GB of free RAM available at runtime**, including a
  4096-token KV/context — by making the runtime fit the device (mmap'd weights + adaptive
  context) instead of assuming desktop-like resources.
- A **device-specific native build** (single `armv8.2-a + dotprod` backend, arm64-only) rather
  than shipping generic unused backends.
- A professional app experience: in-app model switching, live stats, a metrics graph, and an
  Auto/manual tuning layer — treating **efficiency, fit, and sustained usability** as seriously
  as raw speed.

## What we learned

Good on-device AI is not just about loading a model and generating text. We learned that:
- hardware-aware scheduling matters a lot on Arm phones
- mobile inference is shaped by memory bandwidth, heat, and battery, not just compute
- smaller models or smaller quantizations are **not automatically faster** — on this CPU the
  4-bit dotprod (Q4_0) kernel path can beat a "smaller" 3-bit format
- user experience depends on TTFT, responsiveness, and stability, not only tokens/sec
- the difference between a demo and a usable product is often in the **runtime engineering**

The biggest lesson: optimization on Arm is about **making AI fit the device well over time**,
not just making one benchmark number look good.

## What's next for Project ENTITY

- push the adaptive runtime logic further (per-workload, thermal- and battery-aware policies)
- complete cleaner in-app benchmarking and optimized-vs-baseline comparison
- explore prompt/context reuse to cut TTFT on multi-turn chats
- keep reducing overhead and improving sustained efficiency
- ship a stripped release build and package for wider testing
- test the same ideas on more Arm devices beyond a single phone

Long term, we want ENTITY to show that **private, offline, Arm-optimized AI on everyday phones
is practical and worth building seriously**.

---

# Judges' / organizers' form answers

## What was the hardest part of building or optimizing your project?
*(select all that apply)*
- ✅ Setting up the development environment
- ✅ Understanding Arm-specific guidance
- ✅ Measuring performance
- ✅ Improving model speed or latency
- ✅ Reducing model size or memory usage
- ✅ Debugging runtime or compatibility issues
- ✅ Finding relevant examples or documentation
- ✅ Knowing which tools to use

*(not selected: Improving inference server performance; Migrating from another architecture;
Finding compatible hardware or cloud instances)*

## What would have made it easier to complete your project?
*(select all that apply)*
- ✅ More sample projects
- ✅ Clearer setup instructions
- ✅ More Arm-specific optimization guidance
- ✅ More benchmarking examples
- ✅ Better documentation
- ✅ More office hours or live technical support
- ✅ More guidance on what judges were looking for

## Did this challenge change your likelihood of building on Arm in the future?
**Yes, significantly more likely**

## How likely are you to continue developing, optimizing, or deploying this project after the challenge?
**Very likely**

## What is one thing Arm could improve to better support developers like you?

> A single official end-to-end Android LLM reference that covers the whole path in one place —
> project setup, big.LITTLE core pinning and thread/affinity tuning, KleidiAI/dotprod
> enablement, quantization trade-offs (e.g. why Q4_0 can beat a smaller 3-bit format on these
> kernels), on-device thermal/power measurement, and reproducible baseline-vs-optimized numbers
> on a real Arm phone. Today these are scattered across llama.cpp, NDK, and Android docs, and the
> big.LITTLE scheduling and per-variant CPU-backend choices had to be discovered by trial and
> error.
