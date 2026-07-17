#!/data/data/com.termux/files/usr/bin/bash
# size vs speed across quantizations of Llama 1B
cd ~/llama.cpp
bin=./build/bin/llama-cli
opt="-t 4 -Cr 4-7 --cpu-strict 1 --prio 3"
prompt="Explain the benefits of running AI models locally on a smartphone."

echo "quant,size_MB,gen_tps"
for q in Q8_0 Q4_0 Q3_K_L IQ3_M; do
  m=~/models/Llama-3.2-1B-Instruct-$q.gguf
  [ -f "$m" ] || continue
  size=$(du -m "$m" | cut -f1)
  tps=$($bin -m "$m" -st --ignore-eos -c 512 -p "$prompt" -n 100 $opt 2>&1 \
        | grep -oE "Generation: [0-9.]+" | grep -oE "[0-9.]+")
  echo "$q,$size,$tps"
done
