cd /data/local/tmp/qlab/bin
export LD_LIBRARY_PATH=.
M=/sdcard/Models
for f in 1b-q8_0 1b-q4_k_m 1b-q4_0-imat 1b-q4_0-kopt 1b-q3_k_l 1b-q4_0-stock; do
  [ -f "$M/$f.gguf" ] || { echo "MISSING $f"; continue; }
  echo ""
  echo "########## $f ##########"
  taskset f0 ./llama-bench -m $M/$f.gguf -p 512 -n 128 -t 4 -r 3 2>&1 | grep -viE "^load|warn"
  echo "--- cooling 90s ---"
  sleep 90
done
echo "SWEEP DONE"
