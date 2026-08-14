#!/system/bin/sh
# ENTITY ISA-dispatch lab - on-device runner.
#
# The question
# ------------
# ENTITY ships one APK carrying seven CPU backend variants and picks one at startup by
# scoring each against the chip's ISA. That is a claim about a decision, and it has never
# been priced: nobody measured what the chosen variant is worth against the ones it beat.
#
# The usual way to argue this is a cross-device chart - an Armv8.0 phone against an ARMv9
# phone - but that comparison is confounded by everything else that differs between two
# phones: memory bandwidth, cluster width, thermal budget, DVFS policy. It shows that
# newer silicon is faster, which nobody doubted.
#
# This runs every variant the binary contains on ONE device, same model, same thread
# count, same pinned cores, same thermal protocol. The only thing that changes between
# arms is which instruction set the kernels are allowed to use. Whatever the numbers do,
# they are attributable to that and to nothing else.
#
# How a variant is forced
# -----------------------
# llama.cpp with GGML_BACKEND_DL scans a directory, calls ggml_backend_score() on every
# libggml-cpu-*.so it finds, and loads the highest scorer that the CPU can actually run.
# GGML_BACKEND_PATH only *adds* a backend, so it cannot be used to hold one back. The
# reliable lever is the directory: stage one folder per variant, each containing the core
# libraries plus exactly one CPU backend, and point the loader at it.
#
# The variant that actually loaded is read back out of llama.cpp's own log line rather
# than assumed, so a staging mistake shows up as a mismatch instead of a wrong number.
#
# The ladder, and what each rung adds (ggml/src/CMakeLists.txt):
#
#   android_armv8.0_1   baseline NEON, no dotprod
#   android_armv8.2_1   + DOTPROD
#   android_armv8.2_2   + FP16 vector arithmetic
#   android_armv8.6_1   + MATMUL_INT8 (i8mm)
#   android_armv9.0_1   + SVE2
#   android_armv9.2_1   + SVE, SME
#   android_armv9.2_2   + SVE2 as well
#
# A variant whose instructions the CPU lacks will not load; those rungs are reported as
# skipped, which is itself the measurement of where this phone sits on the ladder.
#
# Reading the result
# ------------------
# Prompt processing is the number to watch. It is compute-bound and runs through the
# integer matmul kernels, which is exactly what these ISA extensions change. Decode is
# memory-bandwidth-bound and should move much less - if it moves a lot, something other
# than the kernel changed and the run is suspect.
#
# Usage:  sh /data/local/tmp/qlab/isa-dispatch.sh
# Host staging and analysis: quant-lab/stage-isa-dispatch.sh

set -u
BASE=/data/local/tmp/qlab
BIN=$BASE/bin
STAGE=$BASE/isa
OUT=$BASE/results/isa-dispatch

MODEL=${MODEL:-/sdcard/Models/1b-q4_0-stock.gguf}
THREADS=${THREADS:-4}
MASK=${MASK:-f0}
PP=${PP:-512}
TG=${TG:-128}
REPS=${REPS:-3}
COOL=${COOL:-60}

mkdir -p "$OUT"
log() { echo "[isa] $*"; }

[ -f "$MODEL" ] || { log "FATAL: model not found at $MODEL"; exit 1; }
[ -d "$STAGE" ] || { log "FATAL: variant folders missing - run stage-isa-dispatch.sh push"; exit 1; }

log "model   $MODEL"
log "config  $THREADS threads, affinity $MASK, pp$PP tg$TG, $REPS reps per variant"

# The CPU's own account of what it supports, recorded once so the skipped rungs below can
# be checked against it rather than taken on trust.
grep -m1 "^Features" /proc/cpuinfo > "$OUT/cpu-features.txt" 2>/dev/null
log "cpu     $(cat "$OUT/cpu-features.txt" 2>/dev/null | cut -c1-120)"

VARIANTS="android_armv8.0_1 android_armv8.2_1 android_armv8.2_2 android_armv8.6_1 android_armv9.0_1 android_armv9.2_1 android_armv9.2_2"

for v in $VARIANTS; do
  d="$STAGE/$v"
  if [ ! -d "$d" ]; then
    log "=== $v: not staged, skipping"
    echo "notstaged" > "$OUT/$v.status"
    continue
  fi

  # Thermal control before every arm, in every arm, so arm order cannot favour the last.
  log "cooling ${COOL}s before $v"
  sleep "$COOL"

  log "=== $v"
  export LD_LIBRARY_PATH="$d"
  taskset "$MASK" "$d/llama-bench" \
      -m "$MODEL" -p "$PP" -n "$TG" -t "$THREADS" -r "$REPS" \
      > "$OUT/$v.txt" 2> "$OUT/$v.log"
  rc=$?

  # llama.cpp names the backend it loaded; take the answer from there, not from the folder.
  loaded=$(grep -o "libggml-cpu-[a-z0-9._]*\.so" "$OUT/$v.log" 2>/dev/null | head -1)
  echo "${loaded:-none}" > "$OUT/$v.loaded"

  if [ "$rc" != "0" ] || ! grep -q "|" "$OUT/$v.txt" 2>/dev/null; then
    # Two very different failures land here and must not be conflated. A variant whose
    # instructions this CPU lacks is a RESULT - it is the measurement of where the chip
    # sits on the ladder. A dynamic linker error is a STAGING BUG in this harness, and
    # reporting it as "unsupported" would publish a missing library as a property of the
    # silicon. The first version of this script did exactly that on all seven rungs.
    # Note the -E: toybox grep on Android does not honour \| alternation in a basic
    # regex, so the first version of this check silently never matched and reported a
    # missing library as an unsupported CPU on all seven rungs.
    if grep -qiE "CANNOT LINK|library .* not found|No such file" "$OUT/$v.log" 2>/dev/null; then
      log "$v FAILED TO LOAD - harness bug, not a CPU limit:"
      grep -iE "CANNOT LINK|library .* not found" "$OUT/$v.log" | head -1
      echo "stagingerror" > "$OUT/$v.status"
    else
      log "$v did not run (rc=$rc) - this CPU does not support it"
      echo "unsupported" > "$OUT/$v.status"
    fi
  else
    echo "ok" > "$OUT/$v.status"
    log "$v loaded ${loaded:-?}"
    grep -E "pp|tg" "$OUT/$v.txt" | tail -2
  fi
done

log "raw output in $OUT - pull it with stage-isa-dispatch.sh pull"
