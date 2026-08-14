#!/usr/bin/env bash
# Host side of the ISA-dispatch lab: stage one folder per CPU backend variant, run, pull,
# analyse. The measurement itself is isa-dispatch.sh and runs entirely on the phone.
#
#   ./stage-isa-dispatch.sh push     stage the seven variant folders
#   ./stage-isa-dispatch.sh run      run every variant on the device (~15 min at REPS=3)
#   ./stage-isa-dispatch.sh pull     copy raw output into results/isa-dispatch/
#   ./stage-isa-dispatch.sh report   analyse and print the ladder
#   ./stage-isa-dispatch.sh all      all four in order
#
# Each staged folder holds the core libraries plus exactly one libggml-cpu-*.so, because
# llama.cpp picks the best-scoring backend in whatever directory it is told to scan.
# Isolating by directory is the only way to hold a variant back - GGML_BACKEND_PATH adds
# backends, it cannot remove them.

set -euo pipefail

# Git Bash rewrites absolute POSIX paths before handing them to a native .exe, which turns
# /data/local/tmp into C:/Program Files/Git/data/local/tmp. Host paths still need the
# rewrite, so only the device prefixes are excluded.
export MSYS2_ARG_CONV_EXCL='/data;/sdcard'

LAB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$LAB/../llama.cpp/build-android/bin"
BASE=/data/local/tmp/qlab
STAGE=$BASE/isa
RESULTS="$LAB/results/isa-dispatch"

ADB="${ADB:-adb}"
MODEL_ON_DEVICE="${MODEL_ON_DEVICE:-/sdcard/Models/1b-q4_0-stock.gguf}"
THREADS="${THREADS:-4}"
MASK="${MASK:-f0}"
PP="${PP:-512}"
TG="${TG:-128}"
REPS="${REPS:-3}"
COOL="${COOL:-60}"

VARIANTS=(android_armv8.0_1 android_armv8.2_1 android_armv8.2_2 android_armv8.6_1
          android_armv9.0_1 android_armv9.2_1 android_armv9.2_2)

# Everything a llama-bench process needs except the CPU backend itself.
CORE_LIBS=(libggml-base.so libggml.so libllama.so libllama-common.so libllama-bench-impl.so)

# libomp.so is an NDK runtime library, not a build output, so it lives outside BUILD and
# has to be found separately. libggml-base.so links against it, and without it every
# variant dies at the loader with CANNOT LINK EXECUTABLE - which looks exactly like "this
# CPU does not support that variant" unless the stderr is read.
NDK_OMP="${NDK_OMP:-$(ls -1 "${ANDROID_NDK_HOME:-$LOCALAPPDATA/Android/Sdk/ndk}"/*/toolchains/llvm/prebuilt/*/lib/clang/*/lib/linux/aarch64/libomp.so 2>/dev/null | head -1)}"

die() { echo "error: $*" >&2; exit 1; }
adb_shell() { MSYS2_ARG_CONV_EXCL='*' "$ADB" shell "$@"; }

require_device() {
  local n
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)
  [ "$n" -eq 1 ] || die "expected exactly one device in 'adb devices', found $n"
}

cmd_push() {
  require_device
  [ -f "$BUILD/llama-bench" ] || die "llama-bench not built for android in $BUILD"
  [ -n "$NDK_OMP" ] && [ -f "$NDK_OMP" ] || die "libomp.so not found - set NDK_OMP to the aarch64 copy in your NDK. Without it every variant dies at the linker and looks unsupported."

  adb_shell "rm -rf $STAGE; mkdir -p $STAGE $BASE/results/isa-dispatch"
  for v in "${VARIANTS[@]}"; do
    local so="$BUILD/libggml-cpu-$v.so"
    if [ ! -f "$so" ]; then
      echo "skip $v - not in this build"
      continue
    fi
    echo "staging $v"
    adb_shell "mkdir -p $STAGE/$v"
    "$ADB" push "$so" "$STAGE/$v/" >/dev/null
    for l in "${CORE_LIBS[@]}"; do
      [ -f "$BUILD/$l" ] && "$ADB" push "$BUILD/$l" "$STAGE/$v/" >/dev/null
    done
    [ -n "$NDK_OMP" ] && [ -f "$NDK_OMP" ] && "$ADB" push "$NDK_OMP" "$STAGE/$v/" >/dev/null
    "$ADB" push "$BUILD/llama-bench" "$STAGE/$v/" >/dev/null
    adb_shell "chmod 755 $STAGE/$v/llama-bench"
  done

  "$ADB" push "$LAB/isa-dispatch.sh" "$BASE/isa-dispatch.sh" >/dev/null
  adb_shell "ls -1 $MODEL_ON_DEVICE" >/dev/null 2>&1 || die "$MODEL_ON_DEVICE is not on the device"
  echo "staged $(adb_shell "ls -1 $STAGE | wc -l" | tr -d '\r') variant folders"
}

cmd_run() {
  require_device
  echo "running every staged variant - same model, threads and cores; only the ISA changes"
  adb_shell "MODEL=$MODEL_ON_DEVICE THREADS=$THREADS MASK=$MASK PP=$PP TG=$TG REPS=$REPS COOL=$COOL sh $BASE/isa-dispatch.sh"
}

cmd_pull() {
  require_device
  mkdir -p "$RESULTS"
  "$ADB" pull "$BASE/results/isa-dispatch/." "$RESULTS/" >/dev/null
  echo "pulled into $RESULTS"
}

cmd_report() {
  [ -d "$RESULTS" ] || die "nothing pulled yet - run \`$0 pull\`"

  echo
  echo "================ ISA DISPATCH, ONE DEVICE ================"
  echo "Same model, same thread count, same pinned cores. Only the CPU backend differs."
  [ -f "$RESULTS/cpu-features.txt" ] && echo "CPU: $(cut -c1-100 < "$RESULTS/cpu-features.txt")"
  echo

  printf "%-20s %-12s %12s %12s  %s\n" VARIANT STATUS "PROMPT tok/s" "DECODE tok/s" "BACKEND LOADED"
  local base_pp="" base_tg=""
  for v in "${VARIANTS[@]}"; do
    local f="$RESULTS/$v.txt" st="missing" pp="-" tg="-" loaded="-"
    [ -f "$RESULTS/$v.status" ] && st=$(tr -d '\r\n' < "$RESULTS/$v.status")
    [ -f "$RESULTS/$v.loaded" ] && loaded=$(tr -d '\r\n' < "$RESULTS/$v.loaded")
    if [ -f "$f" ]; then
      # llama-bench prints a markdown table; the pp and tg rows carry the rate in col 2.
      pp=$(grep -E "\| *pp" "$f" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}' || true)
      tg=$(grep -E "\| *tg" "$f" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}' || true)
    fi
    printf "%-20s %-12s %12s %12s  %s\n" "$v" "$st" "${pp:--}" "${tg:--}" "$loaded"
    if [ "$st" = "ok" ] && [ -z "$base_pp" ]; then base_pp="$pp"; base_tg="$tg"; fi
  done

  echo
  echo "Relative to the lowest rung that ran:"
  for v in "${VARIANTS[@]}"; do
    local f="$RESULTS/$v.txt"
    [ -f "$f" ] || continue
    [ "$(tr -d '\r\n' < "$RESULTS/$v.status" 2>/dev/null)" = "ok" ] || continue
    local pp tg
    pp=$(grep -E "\| *pp" "$f" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}' || true)
    tg=$(grep -E "\| *tg" "$f" | tail -1 | awk -F'|' '{gsub(/ /,"",$(NF-1)); print $(NF-1)}' || true)
    awk -v v="$v" -v pp="$pp" -v tg="$tg" -v bpp="$base_pp" -v btg="$base_tg" \
      'BEGIN{ if (bpp+0>0 && pp+0>0) printf "  %-20s prompt %+7.1f%%   decode %+7.1f%%\n", v, (pp/bpp-1)*100, (tg/btg-1)*100 }'
  done

  echo
  echo "How to read this:"
  echo "  Prompt should move most - it is compute-bound and runs the integer matmul kernels"
  echo "  these extensions change. Decode is bandwidth-bound and should barely move; if it"
  echo "  moves a lot, something other than the kernel changed and the run is suspect."
  echo "  'unsupported' rungs are the measurement of where this CPU sits on the ladder."
  echo
  echo "raw output: $RESULTS"
}

case "${1:-all}" in
  push) cmd_push ;;
  run) cmd_run ;;
  pull) cmd_pull ;;
  report) cmd_report ;;
  all) cmd_push; cmd_run; cmd_pull; cmd_report ;;
  *) die "unknown command: $1 (push|run|pull|report|all)" ;;
esac
