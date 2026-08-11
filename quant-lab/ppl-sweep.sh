#!/usr/bin/env bash
# Perplexity sweep for the ENTITY quantization-quality table.
#
# Method notes that belong in the writeup:
#   - Every quant is produced from ONE F16 file (bartowski Llama-3.2-1B-Instruct-f16),
#     so a PPL difference is the quantizer's, not the publisher's.
#   - Calibration set (calibration_datav3.txt) and evaluation set (wikitext-2 test)
#     are disjoint. Calibrating on the eval set would inflate the imatrix result.
#   - THREADS and CHUNKS are held constant across every run. ggml's reduction order
#     depends on thread count, so varying it between runs makes them incomparable.
#   - Perplexity is a deterministic function of weights and eval text; it is computed
#     on host. Only the SPEED numbers require the phone, and those come from
#     ENTITY Bench on the catalog files already installed there.

set -u
LAB="C:/CLAUDE PROJECTS/ARM/quant-lab"
PPL="C:/CLAUDE PROJECTS/ARM/llama.cpp/build-host/bin/llama-perplexity.exe"
EVAL="$LAB/wikitext-2-raw/wiki.test.raw"
THREADS=8
CHUNKS=200
mkdir -p "$LAB/results"

run() {
  tag=$1; model=$2
  out="$LAB/results/ppl-$tag.txt"
  if [ -s "$out" ] && grep -q "Final estimate" "$out"; then
    echo "[skip] $tag already measured"; return
  fi
  if [ ! -f "$model" ]; then echo "[skip] $tag: $model missing"; return; fi
  echo "[run ] $tag  ($(stat -c%s "$model") bytes)"
  "$PPL" -m "$model" -f "$EVAL" --chunks "$CHUNKS" -t "$THREADS" > "$out" 2>&1
  grep -oE "Final estimate: PPL = [0-9.]+ \+/- [0-9.]+" "$out" | tail -1 || echo "       (no PPL line)"
}

# Reference first so every later Δ has something to be relative to.
run f16        "$LAB/models/Llama-3.2-1B-Instruct-f16.gguf"
run q8_0       "$LAB/out/1b-q8_0.gguf"
run q4_0-stock "$LAB/out/1b-q4_0-stock.gguf"
run q4_0-imat  "$LAB/out/1b-q4_0-imat.gguf"
run q4_k_m     "$LAB/out/1b-q4_k_m.gguf"
run q3_k_l     "$LAB/out/1b-q3_k_l.gguf"

echo
echo "================ RESULTS ================"
printf "%-12s %-10s %-12s %s\n" TAG PPL "SIZE_BYTES" KLEIDIAI
for t in f16 q8_0 q4_0-stock q4_0-imat q4_k_m q3_k_l; do
  f="$LAB/results/ppl-$t.txt"
  [ -f "$f" ] || continue
  p=$(grep -oE "Final estimate: PPL = [0-9.]+" "$f" | tail -1 | awk '{print $NF}')
  case "$t" in
    f16)   m="$LAB/models/Llama-3.2-1B-Instruct-f16.gguf" ;;
    *)     m="$LAB/out/1b-$t.gguf" ;;
  esac
  s=$(stat -c%s "$m" 2>/dev/null || echo na)
  case "$t" in
    q4_0-stock|q4_0-imat|q8_0) k=yes ;;
    f16)                       k="n/a" ;;
    *)                         k=NO ;;
  esac
  printf "%-12s %-10s %-12s %s\n" "$t" "${p:-FAILED}" "$s" "$k"
done
