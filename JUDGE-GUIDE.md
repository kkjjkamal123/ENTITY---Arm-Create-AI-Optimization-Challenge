# Judge guide — verify ENTITY in five minutes

No build, no toolchain, no SDK. One APK, one model, one tap. Everything below runs
offline on any arm64 Android 13+ phone.

If you have five minutes, do **Path A**. If you have twenty and want to check the
central claim yourself, do **Path B**.

---

## Path A — the claim, in five minutes

**1. Install the benchmark app** (10 MB, release-signed):

```bash
adb install -r apk/ENTITY-Bench-v2.1.1-release.apk
```

Or copy the APK to the phone and tap it.

**2. Open ENTITY Bench and download a model.** The home screen has a model catalog
sized for the phone in hand. Pick **Llama 3.2 1B Instruct Q4_0** (773 MB) — it is
tagged `RECOMMENDED` on most devices and it is the quantization every published number
here uses. No account, no login.

**3. Run the ablation.** Tap **RUN**, leave the default PP 512 / TG 128, set runs to
**5**, and start. The app runs the same workload four ways, with a thermal cooldown
between every pass:

| Arm | What it is |
|---|---|
| `naive` | 8 threads, default scheduler — what an untuned llama.cpp build does |
| `threads_only` | ENTITY's derived thread count, **no** affinity — what `llama.cpp -t N` does |
| `optimized` | ENTITY's shipped path: derived threads, pinned to the performance cluster |
| `efficiency` | same thread count pinned to the LITTLE cores |

**4. Read the result.** The middle arm is the point. `naive → threads_only` isolates
the thread count; `threads_only → optimized` isolates the core pinning. The number
ENTITY claims is attributed, not asserted.

Expected decode, five runs, unplugged:

| Device | naive | threads_only | optimized |
|---|---:|---:|---:|
| CMF Phone 1 (Dimensity 7300) | ~10.8 | ~15.0 | ~18.1 tok/s |
| OPPO CPH2729 (Snapdragon 6 Gen 4) | ~9.7 | ~17.4 | ~17.5 tok/s |

Different silicon will give different numbers — that is the finding, not a defect.
**The thread count is the universal earner; what pinning adds is device-dependent.**
On the Dimensity it is +21% decode; on the Snapdragon it is +1% decode but ~30% lower
median power.

**5. Export.** Tap **EXPORT CSV** for every individual pass, including the CPU mask the
kernel actually applied. A failed `sched_setaffinity` cannot silently pass as "pinning
earns nothing" — the mask is in the file.

That is the whole claim, measured on your hardware, in five minutes.

---

## Path B — check the KleidiAI finding

The project's second claim is that **Arm's KleidiAI has matmul kernels for `Q4_0` and
`Q8_0` only**, so every other quantization silently falls back to generic ggml.

Download **Llama 3.2 1B Instruct Q4_K_M** from the same in-app catalog and run the same
benchmark. Compare its prompt-processing number against Q4_0's. The two files are 4%
apart in size and differ only in quantization.

The app tells you which case you are in before you run: the model card shows a KleidiAI
pill, filled when the loaded quantization can reach Arm's kernels and a dashed outline
when it cannot.

This finding was contributed upstream and **merged into llama.cpp**:
[PR #25701](https://github.com/ggml-org/llama.cpp/pull/25701), reviewed by
`chaxu01` (Arm, KleidiAI backend maintainer), merged 2026-07-21 as `fb0e6b6`. The
warning now ships to every llama.cpp user.

---

## The chat app

`apk/ENTITY-v24-entity-identity-prompt-20260724-release.apk` (10 MB) is the consumer
app — fully offline chat, live token/watt telemetry, the same benchmark built in. It is
what the optimization is *for*; ENTITY Bench is how it is proved.

---

## If you would rather read than run

| Question | File |
|---|---|
| What is the complete submission? | [`github.md`](github.md) |
| What exactly was measured, and how? | [`benchmarks/REPRODUCIBILITY.md`](benchmarks/REPRODUCIBILITY.md) |
| What are the raw numbers? | [`benchmarks/BENCHMARKS.md`](benchmarks/BENCHMARKS.md) + per-pass CSVs in [`benchmarks/results/`](benchmarks/results/) |
| What did the project get **wrong**? | [`docs/JOURNEY.md`](docs/JOURNEY.md) — every withdrawn claim, including the headline the ablation disproved |
| What did contributors' devices show? | [`benchmarks/CONTRIBUTED-DATA.md`](benchmarks/CONTRIBUTED-DATA.md) — 22 rows, 9 SoCs, devices the author does not own |
| How does it compare to other apps? | [`benchmarks/competitor-comparison/`](benchmarks/competitor-comparison/) — same phone, same model file, same workload |

Live leaderboard: <https://kkjjkamal123.github.io/ENTITY-WEB/>
Demo video: <https://youtu.be/ZD_jpyBqkF8>

---

## Building from source, if you want to

Not required for any of the above. See [`SETUP.md`](SETUP.md) — the app builds inside a
llama.cpp checkout, and `setup.sh` lays that out for you.
