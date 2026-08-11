#!/system/bin/sh
# ENTITY quantization-quality lab — on-device runner.
#
# Everything runs on the phone: quantization, importance-matrix computation, perplexity.
# The host only stages files. That is the point — every number lands on Arm silicon.
#
# Protocol notes that matter for the writeup:
#   - Calibration (calibration_datav3.txt) and evaluation (wiki.test.raw) are DISJOINT.
#     Calibrating on the eval set would inflate the imatrix result.
#   - Every quant is produced from the SAME F16 file, so any difference is the
#     quantizer's, not the publisher's.
#   - CHUNKS is fixed across every perplexity run. Varying it makes runs incomparable.
#   - Each quant is deleted after its perplexity run (KEEP=0) to fit constrained
#     storage. Set KEEP=1 to retain them for on-device speed benchmarking.

set -u
BASE=/data/local/tmp/qlab
BIN=$BASE/bin
export LD_LIBRARY_PATH=$BIN
CHUNKS=${CHUNKS:-200}
THREADS=${THREADS:-4}
KEEP=${KEEP:-0}
F16=$BASE/models/Llama-3.2-1B-Instruct-f16.gguf
IMAT=$BASE/llama-1b.imatrix
OUT=$BASE/results
mkdir -p "$OUT" "$BASE/models"

log() { echo "[qlab] $*"; }
space() { df /data | tail -1 | awk '{print "[qlab] free: " int($4/1024) " MB"}'; }

log "chunks=$CHUNKS threads=$THREADS keep=$KEEP"
space

# ---------------------------------------------------------------- imatrix
if [ ! -f "$IMAT" ]; then
  log "computing importance matrix (calibration set, disjoint from eval)"
  "$BIN/llama-imatrix" -m "$F16" -f "$BASE/calibration_datav3.txt" \
      -o "$IMAT" --chunks 100 -t "$THREADS" 2>&1 | tail -4
  [ -f "$IMAT" ] || { log "FATAL: imatrix not produced"; exit 1; }
fi
log "imatrix ready: $(ls -l "$IMAT" | awk '{print $5}') bytes"

# ---------------------------------------------------------------- one quant end-to-end
# quantize -> perplexity -> record size -> (optionally) delete
run_one() {
  tag=$1; type=$2; extra=$3
  f="$BASE/models/1b-$tag.gguf"
  o="$OUT/ppl-$tag.txt"

  if [ -f "$o" ] && [ -s "$o" ]; then log "$tag already measured, skipping"; return 0; fi

  if [ ! -f "$f" ]; then
    log "quantize -> $tag ($type) ${extra:+with imatrix}"
    # shellcheck disable=SC2086
    "$BIN/llama-quantize" $extra "$F16" "$f" "$type" "$THREADS" 2>&1 | tail -2
  fi
  [ -f "$f" ] || { log "SKIP $tag: quantize failed"; return 1; }

  sz=$(ls -l "$f" | awk '{print $5}')
  echo "size_bytes $sz" > "$OUT/size-$tag.txt"
  log "$tag size ${sz} bytes"

  log "perplexity $tag (chunks=$CHUNKS)"
  "$BIN/llama-perplexity" -m "$f" -f "$BASE/wiki.test.raw" \
      --chunks "$CHUNKS" -t "$THREADS" 2>&1 | tail -40 > "$o"
  grep -E "Final estimate|PPL" "$o" | tail -2 || log "$tag: no PPL line — check $o"

  if [ "$KEEP" = "0" ]; then rm -f "$f"; log "removed $tag to reclaim space"; fi
  space
}

# Order matters: cheapest and most important first, so a run that dies on the last
# model still leaves the headline comparison (stock vs imatrix Q4_0) complete.
run_one q4_0-stock Q4_0   ""
run_one q4_0-imat  Q4_0   "--imatrix $IMAT"
run_one q4_k_m     Q4_K_M ""
run_one q3_k_l     Q3_K_L ""
run_one q8_0       Q8_0   ""

# F16 reference last: 2.36 GB resident against ~1.6 GB MemAvailable, so it is the run
# most likely to be OOM-killed. If it dies, Q8_0 is the reference and the writeup says so.
if [ ! -f "$OUT/ppl-f16.txt" ]; then
  log "perplexity f16 reference (may be killed for memory — that is expected here)"
  "$BIN/llama-perplexity" -m "$F16" -f "$BASE/wiki.test.raw" \
      --chunks "$CHUNKS" -t "$THREADS" 2>&1 | tail -40 > "$OUT/ppl-f16.txt" \
      || log "F16 perplexity failed — using Q8_0 as reference"
fi

log "=========== RESULTS ==========="
for f in "$OUT"/ppl-*.txt; do
  [ -f "$f" ] || continue
  t=$(basename "$f" .txt | sed 's/^ppl-//')
  p=$(grep -oE "Final estimate: PPL = [0-9.]+" "$f" | tail -1 | awk '{print $NF}')
  s=$(cat "$OUT/size-$t.txt" 2>/dev/null | awk '{print $2}')
  echo "$t  PPL=${p:-FAILED}  size=${s:-na}"
done
