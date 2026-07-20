# ENTITY Bench v1.2.0 — 2026-07-20

**APK:** `ENTITY-Bench-v1.2.0-release.apk` (release)

**Thread sweep: the benchmark stops assuming a thread count and measures it.** The three-arm
ablation answers "does the shipped policy beat the phone's default". It cannot answer the harder
question — "is the shipped policy the best this phone can do" — because it only ever runs one
thread width. The sweep runs **every width the device can use, each one pinned and again left to
the scheduler**, and reports which configuration actually won.

This matters because the thread count is derived from clock frequency, and clock frequency cannot
tell a slow core from a narrow one. A Cortex-A55 at 80% of the prime clock and a full performance
core at 76% of it look identical to the rule and are nothing alike in throughput. Rather than
encode a table of core types that ages with every new SoC, the app measures the device in front of
it.

## Added

- **SWEEP mode**, alongside 3-ARM and SUSTAINED on the home screen. Widths are 2 / 4 / 6 / 8
  capped at the core count, plus whatever Auto derives, so the shipped policy always appears as a
  row in the table it is being judged against.
- **Both placements at every width.** Pinning an explicit count masks to exactly that many of the
  fastest cores, so a pinned/no-pin pair at one width isolates placement while the column isolates
  width — a two-dimensional ablation instead of a single line through it.
- **A result page that names the winner** and says whether Auto already picks it. When it does not,
  it reports how much was left on the table and what to set manually in the chat app.
- **A run-length estimate before starting.** A sweep is widths × 2 placements × runs, each with a
  full cooldown; the confirmation dialog states the pass count and rough duration rather than
  letting someone discover it twenty minutes in.
- Sweeps autosave, appear in history, re-export later, and carry a `benchmark_type` row in the CSV
  so an exported sweep is distinguishable from an exported ablation.

## Unchanged

- The measurement core, cooldown policy, telemetry sampling and CSV row keys. A sweep is the same
  pass the ablation runs, at more configurations.
- The three-arm and sustained modes, and every published result taken with them.

## Reading a sweep

Best is chosen on **decode**, which is what a chat user waits on token by token. If a long first
prompt matters more, read the prompt column; if battery matters more, read tok/W. The columns can
disagree, and when they do that is a real finding about the device, not noise to average away.

One device, one model, one quantization: a sweep answers for that phone. Run it on another and the
answer may differ — which is the entire point of shipping the instrument rather than a constant.
