#!/data/data/com.termux/files/usr/bin/bash
# Detect the performance (big) cores on this Arm SoC and print the optimal
# llama.cpp flags. Pass a model path to launch straight into an optimized chat.

declare -A freq
maxf=0
for d in /sys/devices/system/cpu/cpu[0-9]*; do
  c=$(basename "$d" | tr -dc 0-9)
  f=$(cat "$d/cpufreq/cpuinfo_max_freq" 2>/dev/null) || continue
  freq[$c]=$f
  [ "$f" -gt "$maxf" ] && maxf=$f
done

big=()
for c in $(printf '%s\n' "${!freq[@]}" | sort -n); do
  [ "${freq[$c]}" = "$maxf" ] && big+=("$c")
done

lo=${big[0]}; hi=${big[-1]}; n=${#big[@]}
flags="-t $n -Cr $lo-$hi --cpu-strict 1 --prio 3"
echo "performance cores: ${big[*]} @ $((maxf/1000)) MHz"
echo "optimal flags: $flags"

if [ -n "$1" ]; then
  cd ~/llama.cpp
  ./build/bin/llama-cli -m "$1" $flags -c 4096 --color on \
    -sys "You are ENTITY, a helpful assistant running fully offline."
fi
