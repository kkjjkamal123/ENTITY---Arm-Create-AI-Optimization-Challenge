#!/data/data/com.termux/files/usr/bin/bash
# Fair upstream llama.cpp CPU baseline for ENTITY's PP 512 / TG 128 workload.
#
# Usage:
#   bash scripts/benchmark_vanilla_llama_cpp.sh /path/to/model.gguf [output-directory]
#
# This intentionally uses no CPU mask/range, strict placement, or elevated priority. It is a
# vanilla llama.cpp CPU baseline, not the historical Termux "optimized" experiment.
set -euo pipefail

model=${1:?Usage: $0 /path/to/model.gguf [output-directory]}
out_dir=${2:-./benchmarks/results}
bin=${LLAMA_BENCH_BIN:-"$HOME/llama.cpp/build/bin/llama-bench"}
threads=${LLAMA_BENCH_THREADS:-8}
repeats=${LLAMA_BENCH_REPEATS:-3}

test -x "$bin" || { echo "llama-bench not executable: $bin" >&2; exit 1; }
test -f "$model" || { echo "model not found: $model" >&2; exit 1; }
mkdir -p "$out_dir"

stamp=$(date +%Y%m%d-%H%M%S)
prefix="$out_dir/vanilla-llama-cpp-pp512-tg128-$stamp"

"$bin" --version > "$prefix-version.txt" 2>&1 || true
getprop ro.product.manufacturer > "$prefix-device.txt" 2>&1 || true
getprop ro.product.model >> "$prefix-device.txt" 2>&1 || true
getprop ro.build.version.release >> "$prefix-device.txt" 2>&1 || true
sha256sum "$model" > "$prefix-model-sha256.txt"

# llama-bench's -p and -n values match ENTITY's synthetic PP/TG workload. -r 1 makes each
# invocation a separately retained pass; do not replace this with realtime priority or affinity.
for run in $(seq 1 "$repeats"); do
    "$bin" -m "$model" -p 512 -n 128 -t "$threads" -r 1 -o json --prio 0 \
        > "$prefix-run$run.json"
done

printf 'baseline=upstream_llama_cpp\nthreads=%s\npp=512\ntg=128\nruns=%s\npriority=normal\naffinity=default_scheduler\n' \
    "$threads" "$repeats" > "$prefix-metadata.txt"

echo "Wrote baseline evidence with prefix: $prefix"
