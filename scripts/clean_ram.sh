#!/data/data/com.termux/files/usr/bin/bash
echo "before: $(free -m | awk '/Mem/{print $7}') MB free"
pkill -x llama-cli 2>/dev/null
pkill -x llama-bench 2>/dev/null
sync
sleep 2
echo "after:  $(free -m | awk '/Mem/{print $7}') MB free"
