cd /data/local/tmp/qlab/bin
export LD_LIBRARY_PATH=.
M=/sdcard/Models
for f in Llama-3.2-1B-Instruct-Q4_0.gguf Llama-3.2-1B-Instruct-Q4_0-kopt.gguf; do
  echo ""
  echo "########## $f ##########"
  taskset f0 ./llama-bench -m $M/$f -p 512 -n 128 -t 4 -r 3 2>&1 | grep -viE "^load|^llama_model_load|warn"
  echo "--- cooling 90s ---"
  sleep 90
done
