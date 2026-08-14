#!/system/bin/sh
# ENTITY KV-restore lab - on-device runner.
#
# The claim
# ---------
# ENTITY saves a conversation's KV cache to a file and restores it on the next turn instead
# of re-decoding the whole history (`saveState` / `restoreState`, wrapping llama.cpp's
# `llama_state_seq_*`). The app has shipped that since v2.4.0 and logs a TTFT line on both
# paths, and the number was never published.
#
# It is not a constant-factor optimization. Re-priming an N-token history costs a prefill of
# N tokens, so time-to-first-token on turn k grows linearly with everything said so far.
# Restoring reads a file whose size depends on N but whose cost is a sequential read rather
# than a matmul over every weight. The claim is therefore a change in *shape*:
#
#     re-prime:  TTFT = O(history)
#     restore:   TTFT = O(1) in compute, plus a file read
#
# A single measurement cannot show a shape. This sweeps history length and reports the curve,
# because the interesting result is that one line has slope and the other does not.
#
# The instrument
# --------------
# `llama-completion --prompt-cache FILE --prompt-cache-all` is the same mechanism the app
# uses: llama.cpp writes the KV state to a file and, on a later run with the same prefix,
# loads it and skips the prefill. Using the upstream tool rather than the app keeps the
# measurement independent of ENTITY's UI, and `common_perf_print` reports prompt-eval time
# directly, which is exactly the quantity the app's ttft-hook logs.
#
#   cold  cache absent  -> full prefill of N tokens
#   warm  cache present -> prefill skipped
#
# The prompts are prefixes of the same wikitext file, so a longer arm is a strict superset of
# a shorter one - the way a conversation grows.
#
# Usage:  sh /data/local/tmp/qlab/kv-restore.sh
# Host:   quant-lab/stage-kv-restore.sh

set -u
BASE=/data/local/tmp/qlab
BIN=$BASE/bin
OUT=$BASE/results/kv-restore
export LD_LIBRARY_PATH=$BIN

MODEL=${MODEL:-/sdcard/Models/1b-q4_0-stock.gguf}
THREADS=${THREADS:-4}
MASK=${MASK:-f0}
COOL=${COOL:-20}
# Character counts chosen to land near 176 / 352 / 704 / 1408 tokens on this corpus.
SIZES=${SIZES:-"750 1500 3000 6000"}

mkdir -p "$OUT"
log() { echo "[kv] $*"; }

[ -f "$MODEL" ] || { log "FATAL: model not found at $MODEL"; exit 1; }
[ -f "$BASE/wiki.test.raw" ] || { log "FATAL: corpus missing - run stage-kv-restore.sh push"; exit 1; }
[ -x "$BIN/llama-completion" ] || { log "FATAL: llama-completion missing"; exit 1; }

log "model $MODEL, $THREADS threads, affinity $MASK"
echo "chars tokens cold_ms warm_ms state_bytes" > "$OUT/summary.txt"

for chars in $SIZES; do
  p="$OUT/prompt-$chars.txt"
  c="$OUT/cache-$chars.bin"
  head -c "$chars" "$BASE/wiki.test.raw" > "$p"
  rm -f "$c"

  log "cooling ${COOL}s"
  sleep "$COOL"

  # Cold: no cache file, so the whole prefix is decoded. This is the re-prime path.
  log "=== $chars chars: cold (re-prime)"
  taskset "$MASK" "$BIN/llama-completion" -m "$MODEL" -f "$p" \
      --prompt-cache "$c" --prompt-cache-all -n 1 -t "$THREADS" \
      -no-cnv --no-warmup --temp 0 --seed 42 \
      > "$OUT/cold-$chars.txt" 2> "$OUT/cold-$chars.log"

  sleep "$COOL"

  # Warm: the cache written above is loaded and the prefill is skipped. This is restoreState.
  log "=== $chars chars: warm (restore)"
  taskset "$MASK" "$BIN/llama-completion" -m "$MODEL" -f "$p" \
      --prompt-cache "$c" -n 1 -t "$THREADS" \
      -no-cnv --no-warmup --temp 0 --seed 42 \
      > "$OUT/warm-$chars.txt" 2> "$OUT/warm-$chars.log"

  # common_perf_print reports "prompt eval time = X ms / N tokens".
  cold_ms=$(grep "prompt eval time" "$OUT/cold-$chars.log" | tail -1 | sed 's/.*= *//' | cut -d' ' -f1)
  ntok=$(grep "prompt eval time" "$OUT/cold-$chars.log" | tail -1 | sed 's#.*/ *##' | cut -d' ' -f1)
  warm_ms=$(grep "prompt eval time" "$OUT/warm-$chars.log" | tail -1 | sed 's/.*= *//' | cut -d' ' -f1)
  sz=$(ls -l "$c" 2>/dev/null | awk '{print $5}')

  echo "$chars ${ntok:-?} ${cold_ms:-?} ${warm_ms:-?} ${sz:-?}" >> "$OUT/summary.txt"
  log "$chars chars -> ${ntok:-?} tokens: cold ${cold_ms:-?} ms, warm ${warm_ms:-?} ms, state ${sz:-?} bytes"

  # The state files are large and there are four of them; the measurement is the size, which
  # is already recorded above.
  rm -f "$c"
done

log "=========== SUMMARY ==========="
cat "$OUT/summary.txt"
log "raw output in $OUT"
