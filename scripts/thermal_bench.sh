#!/data/data/com.termux/files/usr/bin/bash
# sustained generation, logging temp and speed over time
cd ~/llama.cpp
bin=./build/bin/llama-cli
opt="-t 4 -Cr 4-7 --cpu-strict 1 --prio 3"
prompt="Write a long detailed essay about the history and future of computing."
out=~/thermal.csv

echo "iter,elapsed_s,temp_C,gen_tps" > "$out"
start=$(date +%s)
for i in $(seq 1 26); do
  tps=$($bin -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf -st --ignore-eos \
        -c 512 -p "$prompt" -n 160 $opt 2>&1 \
        | grep -oE "Generation: [0-9.]+" | grep -oE "[0-9.]+")
  temp=$(timeout 6 termux-battery-status | grep -oE '"temperature": [0-9.]+' | grep -oE '[0-9.]+')
  echo "$i,$(( $(date +%s) - start )),$temp,$tps" >> "$out"
done
