#!/system/bin/sh
# ENTITY output-equivalence lab - on-device runner.
#
# The question
# ------------
# Every speed number this project publishes comes from changing how work is scheduled:
# how many threads decode uses, and which cores they run on. None of that touches the
# weights, the sampler or the arithmetic - so the obvious assumption is that the answer
# the model gives is unchanged and only the wait is shorter.
#
# That assumption is not free. ggml splits a row's dot product across threads and sums
# the partial results, so the thread count sets the order of a floating-point reduction.
# Floating-point addition is not associative. Different order, different last bits;
# different last bits in a logit, and a token that was a near-tie can flip. This
# repository already relies on the effect being real - ppl-sweep.sh holds the thread
# count fixed across runs "because ggml's reduction order depends on thread count" - but
# it has never been measured, and an assumption a project depends on twice is worth one
# measurement.
#
# Either outcome is worth publishing:
#
#   identical -> the speedup is free of any change in output, which nobody has shown for
#                this class of optimization on a phone.
#   divergent -> thread count perturbs the reduction order enough to move greedy decoding,
#                and here is how far and how quickly it does so. That is the more
#                interesting result, and it would put a boundary on every claim on the
#                site that is currently stated without one.
#
# The instrument
# --------------
# Two measurements per arm, coarse and fine.
#
#   generation  llama-completion at temperature 0, fixed seed, fixed prompt, 128 tokens.
#               This is what a user would actually see. Insensitive: a logit has to move
#               far enough to change an argmax before it shows up at all.
#
#   perplexity  llama-perplexity over a fixed wikitext slice, printing a running value
#               per chunk. Each chunk's number is a function of hundreds of logits, so a
#               single perturbed accumulation moves a digit. This is the sensitive
#               instrument, and its per-chunk series says not just whether the arms differ
#               but from which chunk onward.
#
# The arms are the app's own ablation, so a result here speaks directly to the numbers on
# the site rather than to a synthetic configuration nobody ships:
#
#   naive       8 threads, every core            - the baseline the +68% is measured from
#   threads     4 threads, scheduler placement   - the thread-count lever alone
#   auto        4 threads, pinned to the fast cluster - what ENTITY actually runs
#   efficiency  4 threads, pinned to the LITTLE cluster - the opposite placement
#
# Nothing else varies. Same file, same prompt, same batch sizes, same seed. If two arms
# disagree, the thread count or the placement is the only thing that could have done it.
#
# Reading the result
# ------------------
# Comparison is against `auto`, because that is the configuration the app ships and so the
# one whose output would be the one users see. Byte-identical output across arms is the
# strong result. Anything else is reported as the chunk index where the series first
# parts, which is the number that says how large the effect is.
#
# Usage:  sh /data/local/tmp/qlab/equivalence.sh
#         CHUNKS=40 sh ... equivalence.sh      (shorter run)
# Host staging and analysis: quant-lab/stage-equivalence.sh

set -u
BASE=/data/local/tmp/qlab
BIN=$BASE/bin
export LD_LIBRARY_PATH=$BIN

# The quant-lab's own Q4_0, produced on this device from the one F16 that every other
# quant in RESULTS.md came from. Using it rather than a downloaded Q4_0 means the file's
# provenance is already established here, and the perplexity figures this run produces are
# directly comparable to the quantization-quality table.
MODEL=${MODEL:-/sdcard/Models/1b-q4_0-stock.gguf}
CHUNKS=${CHUNKS:-40}
# Chunk total for the perplexity control below. Has to be smaller than CHUNKS and has to
# leave a different number of sequences in the final batch, which is what makes it a
# perturbation rather than a shorter copy of the same run.
BATCH_CHUNKS=${BATCH_CHUNKS:-3}
NGEN=${NGEN:-128}
SEED=${SEED:-42}
COOL=${COOL:-60}
OUT=$BASE/results/equivalence

# A prompt with no randomness in it and enough substance to produce 128 tokens of prose
# rather than a refusal or a one-line answer. Kept on one line so it survives the shell
# and every arm receives the byte-identical string.
PROMPT=${PROMPT:-"Explain in detail how a CPU cache works, and why cache misses are expensive."}

mkdir -p "$OUT"
log() { echo "[equiv] $*"; }

[ -f "$MODEL" ] || { log "FATAL: model not found at $MODEL"; exit 1; }
[ -x "$BIN/llama-completion" ] || { log "FATAL: llama-completion missing - run stage-equivalence.sh"; exit 1; }
[ -x "$BIN/llama-perplexity" ] || { log "FATAL: llama-perplexity missing"; exit 1; }
[ -f "$BASE/wiki.test.raw" ] || { log "FATAL: wiki.test.raw missing - run stage-equivalence.sh"; exit 1; }

log "model   $MODEL"
log "chunks  $CHUNKS   gen tokens $NGEN   seed $SEED"
log "prompt  $PROMPT"

# Arm table: name, thread count, affinity mask ("-" for none).
# f0 is cores 4-7 and 0f is cores 0-3 on every 4+4 device measured here; a device with a
# different topology needs these masks changed, and the effective mask is logged below so
# a wrong one cannot pass silently.
#
# The reference arm is first and the controls run immediately after it, before the
# remaining arms. Ordering matters for a reason that has nothing to do with the physics:
# a phone can run out of battery, be unplugged or be killed halfway through, and a run
# truncated after the reference arm and its controls is still evidence, whereas one
# truncated before them is not. Nothing downstream depends on arm order - each arm is
# independent and every arm is preceded by the same cooldown - so putting the
# validity checks where a partial run can still reach them costs nothing.
#
# ARMS is overridable so a run interrupted partway - a phone that shuts down, a cable that
# drops - can be finished by re-running only the arms that are missing rather than the
# whole set. That is safe here in a way it would not be for a speed benchmark: each arm
# writes its own files, reads none of the others, and is preceded by the same cooldown, so
# an arm measured in a second session is the same measurement as one measured in the first.
# Passing a set that omits the reference arm skips the controls with it, and the comparison
# below then re-reads the reference arm's files from the earlier session.
#
#   ARMS=efficiency:4:0f sh equivalence.sh     (finish an interrupted run)
REF=${REF:-auto}
ARMS=${ARMS:-"auto:4:f0 threads:4:- naive:8:ff efficiency:4:0f"}

run_arm() {
  name=$1; threads=$2; mask=$3

  # Thermal control before every arm, in every arm, so arm order cannot favour the last
  # one. This is the same rule the benchmark protocol uses, for the same reason - though
  # here it matters less, since the quantity under test is arithmetic rather than speed.
  log "cooling ${COOL}s before $name"
  sleep "$COOL"

  if [ "$mask" = "-" ]; then
    set --
    prefix=""
  else
    prefix="taskset $mask"
  fi

  log "=== arm $name: $threads threads, affinity ${mask}"
  # Battery and die temperature per arm, recorded rather than asserted. Neither can change
  # the arithmetic, but if an arm turns out to be the odd one out the first question asked
  # will be whether the phone was in some degraded state for it, and this is the only
  # chance to answer that.
  dumpsys battery 2>/dev/null | grep -E "level:|temperature:" > "$OUT/$name-power.txt" 2>&1
  log "power: $(tr '\n' ' ' < "$OUT/$name-power.txt")"
  # Record what the kernel actually applied. A taskset that silently failed would make
  # two arms identical for the wrong reason, and "the outputs matched" would then be a
  # statement about nothing.
  if [ -n "$prefix" ]; then
    $prefix sh -c 'grep Cpus_allowed_list /proc/self/status' > "$OUT/$name-affinity.txt" 2>&1
  else
    grep Cpus_allowed_list /proc/self/status > "$OUT/$name-affinity.txt" 2>&1
  fi
  log "effective cpus: $(cat "$OUT/$name-affinity.txt")"

  log "generating $NGEN tokens (greedy)"
  # --temp 0 makes sampling argmax, so the only way the text can change is if a logit
  # ordering changed. -no-cnv keeps the raw prompt out of a chat template, which would
  # otherwise inject the model's own formatting and make the comparison less direct.
  # shellcheck disable=SC2086
  $prefix "$BIN/llama-completion" \
      -m "$MODEL" -t "$threads" -p "$PROMPT" -n "$NGEN" \
      --temp 0 --seed "$SEED" -no-cnv --no-warmup -st \
      > "$OUT/$name-gen.txt" 2> "$OUT/$name-gen.log"

  log "perplexity over $CHUNKS chunks"
  # shellcheck disable=SC2086
  $prefix "$BIN/llama-perplexity" \
      -m "$MODEL" -f "$BASE/wiki.test.raw" --chunks "$CHUNKS" -t "$threads" --seed "$SEED" \
      > "$OUT/$name-ppl.txt" 2>&1

  ppl=$(grep -oE "Final estimate: PPL = [0-9.]+" "$OUT/$name-ppl.txt" | tail -1 | awk '{print $NF}')
  log "$name PPL=${ppl:-FAILED}"
}

# ---------------------------------------------------------------- controls
# A test that can only ever print "identical" proves nothing, and this one is at real risk
# of that: if the two files were empty, or the comparison compared a file with itself, the
# verdict would look exactly like the good result. Two controls close that off.
#
#   repeat    the reference configuration, run a second time, unchanged. Must be
#             IDENTICAL - otherwise the run is not deterministic at all and no comparison
#             between arms means anything.
#   control   the reference configuration with one word added to the prompt. Must DIFFER -
#             otherwise the comparison cannot detect a difference and every "identical"
#             above is an artefact.
#
# The perplexity series gets a control of its own, for the same reason the generation half
# needs one: "the per-chunk numbers matched" is only evidence if something can make them
# not match, and the two controls above test the generation comparison, not this one.
#
#   batch     the reference configuration over BATCH_CHUNKS chunks instead of CHUNKS,
#             compared over the chunks the two runs share. Must DIFFER.
#
# llama-perplexity packs n_ctx-sized chunks into one batch, so the chunk total sets how
# many sequences are evaluated together and therefore the shape of the matmuls that score
# them. Chunk 3 is the same 512 tokens of wikitext either way and the model scoring it is
# the same file; only the company it travels in changes. If that moves the number, the
# series is sensitive to accumulation order, and its refusal to move across thread counts
# is a measurement rather than a blunt instrument.
#
# This was found rather than designed: an earlier 3-chunk smoke run of this same script
# scored chunk 1 at 8.3443 where the 12-chunk run scores 8.3423. Same text, same model,
# same binary. That is the perturbation the header of this file used to say did not exist.
run_controls() {
  log "cooling ${COOL}s before controls"
  sleep "$COOL"

  log "=== control: repeat of $REF, must be identical"
  taskset f0 "$BIN/llama-completion" \
      -m "$MODEL" -t 4 -p "$PROMPT" -n "$NGEN" \
      --temp 0 --seed "$SEED" -no-cnv --no-warmup -st \
      > "$OUT/control-repeat-gen.txt" 2> "$OUT/control-repeat-gen.log"

  log "=== control: $REF with a perturbed prompt, must differ"
  taskset f0 "$BIN/llama-completion" \
      -m "$MODEL" -t 4 -p "$PROMPT Answer briefly." -n "$NGEN" \
      --temp 0 --seed "$SEED" -no-cnv --no-warmup -st \
      > "$OUT/control-perturbed-gen.txt" 2> "$OUT/control-perturbed-gen.log"

  log "=========== CONTROLS ==========="
  if cmp -s "$OUT/$REF-gen.txt" "$OUT/control-repeat-gen.txt"; then
    echo "repeat     identical  (PASS - the run is deterministic)"
  else
    echo "repeat     DIFFERS    (FAIL - nothing below this line can be trusted)"
  fi
  if cmp -s "$OUT/$REF-gen.txt" "$OUT/control-perturbed-gen.txt"; then
    echo "perturbed  identical  (FAIL - the comparison cannot detect a real difference)"
  else
    echo "perturbed  differs    (PASS - the comparison has resolving power)"
  fi

  # The perplexity control. Same configuration as the reference arm in every respect that
  # the arms vary - 4 threads, fast cluster, same file, same corpus - so the only thing
  # separating it from $REF is the chunk total.
  log "=== control: $REF over $BATCH_CHUNKS chunks, per-chunk series must differ"
  taskset f0 "$BIN/llama-perplexity" \
      -m "$MODEL" -f "$BASE/wiki.test.raw" --chunks "$BATCH_CHUNKS" -t 4 --seed "$SEED" \
      > "$OUT/control-batch-ppl.txt" 2>&1

  # Compared only over the chunks both runs produced, which is the shorter of the two. Any
  # further and the comparison would be reading text one side never scored.
  grep -oE "\[[0-9]+\][0-9.]+" "$OUT/$REF-ppl.txt" | head -n "$BATCH_CHUNKS" > "$OUT/control-batch-ref-head.txt"
  grep -oE "\[[0-9]+\][0-9.]+" "$OUT/control-batch-ppl.txt" | head -n "$BATCH_CHUNKS" > "$OUT/control-batch-head.txt"
  if [ ! -s "$OUT/control-batch-head.txt" ]; then
    echo "batch      NO OUTPUT  (FAIL - the control run produced no per-chunk series)"
  elif cmp -s "$OUT/control-batch-ref-head.txt" "$OUT/control-batch-head.txt"; then
    echo "batch      identical  (batch shape does not move the series either - see note)"
  else
    echo "batch      differs    (PASS - the per-chunk series can resolve an accumulation change)"
  fi
}

for spec in $ARMS; do
  name=${spec%%:*}
  rest=${spec#*:}
  threads=${rest%%:*}
  mask=${rest#*:}
  run_arm "$name" "$threads" "$mask"
  # The controls belong to the reference arm and are run the moment it exists, so that a
  # run that gets no further than this still answers the only question that has to be
  # answered before any arm comparison can be believed.
  [ "$name" = "$REF" ] && run_controls
done

# ---------------------------------------------------------------- comparison
# Against `auto`, the configuration the app ships.
log "=========== EQUIVALENCE vs $REF ==========="
for spec in $ARMS; do
  name=${spec%%:*}
  [ "$name" = "$REF" ] && continue

  if cmp -s "$OUT/$REF-gen.txt" "$OUT/$name-gen.txt"; then
    gen="identical"
  else
    gen="DIFFERS"
  fi

  # The per-chunk series is printed as "[1]6.1234,[2]6.9876,..." - extracting it gives a
  # high-resolution fingerprint of the logits, one number per chunk.
  grep -oE "\[[0-9]+\][0-9.]+" "$OUT/$REF-ppl.txt" | tail -n "$CHUNKS" > "$OUT/$REF-series.txt"
  grep -oE "\[[0-9]+\][0-9.]+" "$OUT/$name-ppl.txt" | tail -n "$CHUNKS" > "$OUT/$name-series.txt"
  if cmp -s "$OUT/$REF-series.txt" "$OUT/$name-series.txt"; then
    ppl="identical"
  else
    ppl="DIFFERS from chunk $(cmp "$OUT/$REF-series.txt" "$OUT/$name-series.txt" 2>/dev/null | grep -oE "line [0-9]+" | grep -oE "[0-9]+" | head -1)"
  fi

  echo "$name  generation:$gen  per-chunk-perplexity:$ppl"
done

log "raw outputs in $OUT - pull them with stage-equivalence.sh pull"
