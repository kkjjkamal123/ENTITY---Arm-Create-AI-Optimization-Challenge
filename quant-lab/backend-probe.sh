#!/system/bin/sh
# ENTITY backend probe - on-device runner.
#
# The question
# ------------
# ENTITY runs on the CPU. That is a design decision, and until it is measured it is only a
# design decision - "we chose the CPU" and "the CPU is the right choice here" are different
# claims, and only the second one is evidence.
#
# The phone has a Mali GPU that advertises Vulkan compute, so the alternative is real and
# testable rather than hypothetical. llama.cpp ships a Vulkan backend; this builds it and
# runs the identical model, prompt and batch shape through both.
#
# What is NOT tested, and why
# ---------------------------
# There is no NNAPI arm. llama.cpp has no NNAPI backend - `ggml/src` contains cpu, vulkan,
# opencl, cuda, metal and others, and nothing for Android's neural network API. So for this
# stack the NPU question has a structural answer rather than an empirical one: the path does
# not exist to be measured. Saying that is more honest than quietly omitting the arm.
#
# The one thing this must not do
# ------------------------------
# Compare across builds. A CPU number from the main build and a Vulkan number from this one
# would differ in compiler flags, backend registry and CPU variant selection, and the
# difference would be attributed to the GPU. Both arms here come from the SAME binary in
# this directory, so the only thing that changes between them is where the work runs.
#
# The CPU arm therefore uses `-dev none`, not `-ngl 0`. That distinction cost a full run to
# find and is worth stating: with a Vulkan device registered, `-ngl 0` still routes work to
# it, and the CPU arm measured 4.69 tok/s prompt against the 151.62 the same silicon does in
# the shipped build - a "CPU" number 32x too slow, which would have made the GPU look
# competitive. `-dev none` removes the device from the registry entirely and the CPU arm
# returns to 157.7. A control that silently fails to control is worse than no control.
#
# Usage:  sh /data/local/tmp/qlab/backend-probe.sh
# Host:   quant-lab/stage-backend-probe.sh

set -u
BASE=/data/local/tmp/qlab
VK=$BASE/vk
OUT=$BASE/results/backend-probe
export LD_LIBRARY_PATH=$VK

MODEL=${MODEL:-/sdcard/Models/1b-q4_0-stock.gguf}
THREADS=${THREADS:-4}
MASK=${MASK:-f0}
PP=${PP:-512}
TG=${TG:-128}
REPS=${REPS:-3}
COOL=${COOL:-45}

mkdir -p "$OUT"
log() { echo "[bp] $*"; }

[ -f "$MODEL" ] || { log "FATAL: model not found at $MODEL"; exit 1; }
[ -x "$VK/llama-bench" ] || { log "FATAL: vulkan build missing - run stage-backend-probe.sh push"; exit 1; }

log "model $MODEL, pp$PP tg$TG, $REPS reps"

# The GPU's own account of itself. This is the line that explains the result rather than
# just reporting it, so it is captured rather than read off the console once.
"$VK/llama-bench" -m "$MODEL" -p 1 -n 1 -r 1 2>&1 | grep -i "ggml_vulkan" > "$OUT/device.txt"
log "$(cat "$OUT/device.txt" | tail -1 | cut -c1-150)"

log "cooling ${COOL}s"
sleep "$COOL"

# CPU arm: same binary, Vulkan device removed from the registry, pinned as the app pins.
log "=== CPU (-dev none), $THREADS threads pinned to $MASK"
taskset "$MASK" "$VK/llama-bench" -m "$MODEL" -p "$PP" -n "$TG" -t "$THREADS" -dev none -r "$REPS" \
    > "$OUT/cpu.txt" 2> "$OUT/cpu.log"
grep -E "\| *(pp|tg)" "$OUT/cpu.txt" | tail -2

log "cooling ${COOL}s"
sleep "$COOL"

# Vulkan arm: same binary, every layer offloaded to the GPU.
log "=== Vulkan (-ngl 99)"
"$VK/llama-bench" -m "$MODEL" -p "$PP" -n "$TG" -ngl 99 -r "$REPS" \
    > "$OUT/vulkan.txt" 2> "$OUT/vulkan.log"
grep -E "\| *(pp|tg)" "$OUT/vulkan.txt" | tail -2

log "=========== SUMMARY ==========="
echo "arm prompt decode" > "$OUT/summary.txt"
for a in cpu vulkan; do
  pp=$(grep -E "\| *pp" "$OUT/$a.txt" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}')
  tg=$(grep -E "\| *tg" "$OUT/$a.txt" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}')
  echo "$a ${pp:-?} ${tg:-?}" >> "$OUT/summary.txt"
done
cat "$OUT/summary.txt"
log "raw output in $OUT"
