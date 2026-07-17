#!/data/data/com.termux/files/usr/bin/bash
cd ~/llama.cpp
./build/bin/llama-cli \
  -m ~/models/Llama-3.2-1B-Instruct-Q4_0.gguf \
  -t 4 -Cr 4-7 --cpu-strict 1 --prio 3 --mlock \
  -c 4096 --color on \
  -sys "You are ENTITY, a helpful assistant running fully offline on a CMF Phone 1."
