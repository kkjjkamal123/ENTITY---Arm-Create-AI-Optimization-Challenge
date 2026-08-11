#!/usr/bin/env bash
# Host side of the output-equivalence lab: stage, run, pull, analyse.
#
# The measurement itself is equivalence.sh and runs entirely on the phone. This script
# only moves files and reads the result, in the same split run-on-device.sh uses - the
# host must never be where a number is produced, because the whole point is that it landed
# on Arm silicon.
#
#   ./stage-equivalence.sh push     push binaries, libraries, corpus and the runner
#   ./stage-equivalence.sh run      run the measurement on the device (long: ~15-25 min)
#   ./stage-equivalence.sh pull     copy the raw outputs back into results/equivalence/
#   ./stage-equivalence.sh report   analyse what was pulled, print the verdict
#   ./stage-equivalence.sh all      all four in order
#
# Preconditions the script checks rather than assumes:
#   - exactly one device visible to adb, unplugged from a charger is preferable but not
#     required here (this measures arithmetic, not power)
#   - the model already on the phone (default /sdcard/Models/1b-q4_0-stock.gguf, the
#     quant-lab Q4_0 built from the same F16 as RESULTS.md; override with MODEL_ON_DEVICE)
#   - llama-completion and llama-perplexity built for android in ../llama.cpp/build-android
#
# llama-completion is not in the default build-android target set. Build it with:
#   cmake --build llama.cpp/build-android --target llama-completion -j 8

set -euo pipefail

# Git Bash and MSYS rewrite any argument that looks like an absolute POSIX path into a
# Windows one before handing it to a native .exe. That is what makes `adb push ./x
# /data/local/tmp/...` arrive as `C:/Program Files/Git/data/local/tmp/...` and fail
# against a directory that cannot exist.
#
# The rewrite has to stay on for host paths - adb.exe is a Windows binary and cannot stat
# `/c/CLAUDE PROJECTS/...` - so the fix is to exclude the two device prefixes rather than
# to disable conversion wholesale. Unset on Linux and macOS, where it means nothing.
export MSYS2_ARG_CONV_EXCL='/data;/sdcard'

LAB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The llama.cpp checkout is a working-copy neighbour, not part of this repository, so its
# position relative to the lab depends on how the workspace is laid out - a sibling of the
# app repo, or a sibling of the repo containing this lab. Both are searched rather than
# assumed, and BUILD can be set outright for a checkout kept somewhere else entirely:
#   BUILD=/path/to/llama.cpp/build-android/bin ./stage-equivalence.sh push
if [ -z "${BUILD:-}" ]; then
  for candidate in \
    "$LAB/../llama.cpp/build-android/bin" \
    "$LAB/../../llama.cpp/build-android/bin" \
    "$LAB/../../../llama.cpp/build-android/bin"
  do
    [ -d "$candidate" ] && { BUILD="$candidate"; break; }
  done
  # Left pointing at the nearest candidate when none exists, so the "not built" error below
  # names a path a reader can act on rather than an empty string.
  BUILD="${BUILD:-$LAB/../llama.cpp/build-android/bin}"
fi
BASE=/data/local/tmp/qlab
RESULTS="$LAB/results/equivalence"

# adb is not always on PATH on Windows; point ADB at the SDK copy if it is not:
#   ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ./stage-equivalence.sh all
ADB="${ADB:-adb}"
MODEL_ON_DEVICE="${MODEL_ON_DEVICE:-/sdcard/Models/1b-q4_0-stock.gguf}"

CHUNKS="${CHUNKS:-40}"
NGEN="${NGEN:-128}"
COOL="${COOL:-60}"

die() { echo "error: $*" >&2; exit 1; }

# `adb shell` takes one argument that is an entire device-side command line, so nothing in
# it is ever a host path - not the bare paths and not the ones inside VAR=/sdcard/...
# assignments, which the prefix exclusion above does not cover because the argument starts
# with "VAR=". Conversion is therefore disabled outright for these calls and only these.
adb_shell() { MSYS2_ARG_CONV_EXCL='*' "$ADB" shell "$@"; }

require_device() {
  local n
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)
  [ "$n" -eq 1 ] || die "expected exactly one device in 'adb devices', found $n"
}

cmd_push() {
  require_device
  # -f, not -x: these are aarch64 binaries that this host can never execute, and a
  # Windows checkout does not carry a POSIX execute bit at all. The device gets chmod 755
  # below, which is where the bit actually has to be right.
  [ -f "$BUILD/llama-completion" ] || die "llama-completion not built - see the header of this script"
  [ -f "$BUILD/llama-perplexity" ] || die "llama-perplexity not built"

  adb_shell "mkdir -p $BASE/bin $BASE/results/equivalence"
  echo "pushing binaries and libraries"
  for f in llama-completion llama-perplexity; do
    "$ADB" push "$BUILD/$f" "$BASE/bin/" >/dev/null
  done
  # Every .so in the build output, not a hand-listed subset: the CPU backend ships as one
  # shared object per ISA variant and llama.cpp picks at load time, so omitting one turns
  # into a silent fallback to a slower - and differently-accumulating - kernel, which is
  # the one confound this measurement cannot tolerate.
  for f in "$BUILD"/*.so; do
    "$ADB" push "$f" "$BASE/bin/" >/dev/null
  done
  adb_shell "chmod 755 $BASE/bin/llama-completion $BASE/bin/llama-perplexity"

  echo "pushing evaluation corpus"
  "$ADB" push "$LAB/wikitext-2-raw/wiki.test.raw" "$BASE/wiki.test.raw" >/dev/null

  echo "pushing runner"
  "$ADB" push "$LAB/equivalence.sh" "$BASE/equivalence.sh" >/dev/null

  adb_shell "ls -1 $MODEL_ON_DEVICE" >/dev/null 2>&1 \
    || die "$MODEL_ON_DEVICE is not on the device"
  echo "staged"
}

cmd_run() {
  require_device
  # ARMS passes straight through to the runner, so an interrupted run can be finished by
  # asking for only the arms that are missing:
  #   ARMS=efficiency:4:0f ./stage-equivalence.sh run
  # Results live on the device between sessions, so `pull` and `report` afterwards see the
  # arms from both runs together.
  if [ -n "${ARMS:-}" ]; then
    echo "running on device - arms: $ARMS, each with a ${COOL}s cooldown first"
  else
    echo "running on device - four arms, each with a ${COOL}s cooldown first"
  fi
  adb_shell "MODEL=$MODEL_ON_DEVICE CHUNKS=$CHUNKS NGEN=$NGEN COOL=$COOL ${ARMS:+ARMS='$ARMS'} sh $BASE/equivalence.sh"
}

cmd_pull() {
  require_device
  mkdir -p "$RESULTS"
  "$ADB" pull "$BASE/results/equivalence/." "$RESULTS/" >/dev/null
  echo "pulled into $RESULTS"
}

# Analysis lives on the host because it is the part worth keeping in version control next
# to the raw outputs, and because the device shell has no diff worth relying on.
cmd_report() {
  [ -d "$RESULTS" ] || die "nothing pulled yet - run \`$0 pull\`"
  local ref=auto
  [ -f "$RESULTS/$ref-gen.txt" ] || die "reference arm output missing from $RESULTS"

  echo
  echo "================ OUTPUT EQUIVALENCE ================"
  echo "reference arm: $ref (4 threads, pinned to the fast cluster - what ENTITY ships)"
  echo

  printf "%-12s %-8s %-14s %-22s %s\n" ARM PPL GENERATION PER-CHUNK-PPL "EFFECTIVE CPUS"
  for arm in naive threads auto efficiency; do
    local gen="$RESULTS/$arm-gen.txt"
    local ppl="$RESULTS/$arm-ppl.txt"
    [ -f "$ppl" ] || { printf "%-12s %s\n" "$arm" "(missing)"; continue; }

    local final
    final=$(grep -oE "Final estimate: PPL = [0-9.]+" "$ppl" | tail -1 | awk '{print $NF}')

    local cpus
    cpus=$(sed -n 's/.*Cpus_allowed_list:[[:space:]]*//p' "$RESULTS/$arm-affinity.txt" 2>/dev/null | head -1)

    local gverdict pverdict
    if [ "$arm" = "$ref" ]; then
      gverdict="(reference)"; pverdict="(reference)"
    else
      if cmp -s "$RESULTS/$ref-gen.txt" "$gen"; then
        gverdict="identical"
      else
        # How far in do the two texts agree? A single differing byte late in 128 tokens is
        # a very different finding from divergence at token three.
        local prefix
        prefix=$(cmp "$RESULTS/$ref-gen.txt" "$gen" 2>/dev/null | grep -oE "byte [0-9]+" | grep -oE "[0-9]+" | head -1)
        gverdict="differs @byte ${prefix:-?}"
      fi

      grep -oE "\[[0-9]+\][0-9.]+" "$RESULTS/$ref-ppl.txt" > "$RESULTS/$ref-series.txt" || true
      grep -oE "\[[0-9]+\][0-9.]+" "$ppl" > "$RESULTS/$arm-series.txt" || true
      if cmp -s "$RESULTS/$ref-series.txt" "$RESULTS/$arm-series.txt"; then
        pverdict="identical"
      else
        local chunk
        chunk=$(diff "$RESULTS/$ref-series.txt" "$RESULTS/$arm-series.txt" \
                  | grep -oE "^[0-9]+" | head -1)
        pverdict="differs @chunk ${chunk:-?}"
      fi
    fi

    printf "%-12s %-8s %-14s %-22s %s\n" "$arm" "${final:-FAILED}" "$gverdict" "$pverdict" "${cpus:-?}"
  done

  # Controls first in the reading order below, because if either fails the table above is
  # not evidence of anything.
  echo
  echo "controls:"
  for c in repeat perturbed; do
    local f="$RESULTS/control-$c-gen.txt"
    [ -f "$f" ] || { echo "  $c: (missing - re-run with a build of equivalence.sh that has controls)"; continue; }
    if cmp -s "$RESULTS/$ref-gen.txt" "$f"; then same=identical; else same=differs; fi
    case "$c:$same" in
      repeat:identical)     echo "  repeat     identical  PASS - the run is deterministic" ;;
      repeat:differs)       echo "  repeat     differs    FAIL - the table above means nothing" ;;
      perturbed:differs)    echo "  perturbed  differs    PASS - the comparison can resolve a real difference" ;;
      perturbed:identical)  echo "  perturbed  identical  FAIL - the comparison has no resolving power" ;;
    esac
  done

  # The two controls above test the generation comparison. This one tests the per-chunk
  # series, by re-running the reference configuration over a different chunk total - which
  # changes how many sequences share a batch, and so the shape of the matmuls, without
  # changing the text being scored or the thread count doing the scoring.
  local bctl="$RESULTS/control-batch-ppl.txt"
  if [ -f "$bctl" ]; then
    local n
    n=$(grep -oE "\[[0-9]+\][0-9.]+" "$bctl" | wc -l | tr -d ' ')
    if [ "$n" -eq 0 ]; then
      echo "  batch      no series  FAIL - the control produced nothing to compare"
    else
      local refhead ctlhead
      refhead=$(grep -oE "\[[0-9]+\][0-9.]+" "$RESULTS/$ref-ppl.txt" | head -n "$n")
      ctlhead=$(grep -oE "\[[0-9]+\][0-9.]+" "$bctl" | head -n "$n")
      if [ "$refhead" = "$ctlhead" ]; then
        echo "  batch      identical  batch shape does not move the series either"
      else
        echo "  batch      differs    PASS - the per-chunk series can resolve an accumulation change"
        echo "             reference: $(echo "$refhead" | tr '\n' ' ')"
        echo "             control:   $(echo "$ctlhead" | tr '\n' ' ')"
      fi
    fi
  else
    echo "  batch      (missing - re-run the reference arm to produce it)"
  fi

  echo
  echo "How to read this:"
  echo "  Both columns identical everywhere -> scheduling changed the wait and nothing else."
  echo "  Per-chunk differs, generation identical -> the reduction order does move logits,"
  echo "    but not far enough to change an argmax over this sample. Report both."
  echo "  Generation differs -> quote the token index. Every speed claim on the site then"
  echo "    needs the sentence 'output is not bit-identical across thread counts' beside it."
  echo
  echo "raw outputs: $RESULTS"
}

case "${1:-all}" in
  push) cmd_push ;;
  run) cmd_run ;;
  pull) cmd_pull ;;
  report) cmd_report ;;
  all) cmd_push; cmd_run; cmd_pull; cmd_report ;;
  *) die "unknown command: $1 (push|run|pull|report|all)" ;;
esac
