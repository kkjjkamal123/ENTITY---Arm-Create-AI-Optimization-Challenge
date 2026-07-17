#!/data/data/com.termux/files/usr/bin/bash
cd ~/llama.cpp
./build/bin/llama-cli \
  -m ~/models/Llama-3.2-3B-Instruct-Q4_0.gguf \
  -t 8 \
  -c 1024 --color on \
  -sys "You are ENTITY, a helpful assistant running fully offline on a CMF Phone 1."
