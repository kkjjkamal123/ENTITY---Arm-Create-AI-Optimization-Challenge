#!/data/data/com.termux/files/usr/bin/bash
# naive (all cores) vs optimized (big cores pinned + realtime) generation speed
cd ~/llama.cpp
bin=./build/bin/llama-cli
prompt="Explain the benefits of running AI models locally on a smartphone."

speed() {
  $bin -m "$1" -st --ignore-eos -c 1024 -p "$prompt" -n 80 $2 2>&1 \
    | grep -oE "Generation: [0-9.]+" | grep -oE "[0-9.]+"
}

for name in Llama-3.2-1B-Instruct-Q4_0 Llama-3.2-3B-Instruct-Q4_0; do
  m=~/models/$name.gguf
  naive=$(speed "$m" "-t 8")
  opt=$(speed "$m" "-t 4 -Cr 4-7 --cpu-strict 1 --prio 3")
  echo "$name  naive=$naive t/s  optimized=$opt t/s"
done
