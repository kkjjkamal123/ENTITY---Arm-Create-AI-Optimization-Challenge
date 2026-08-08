package com.example.llama

import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

/**
 * A model-free device probe: predicts what a model will do on this phone before a single
 * byte of it is downloaded.
 *
 * The catalog previously ranked models on RAM and ISA flags alone, which answers "will it
 * fit" but not "will it be usable". A user on a 4 GB device could be told a 3B model
 * *fits* and still find it decodes at 3 tok/s. Downloading 2 GB to find that out is the
 * worst possible way to learn it.
 *
 * ## Why this can work without a model
 *
 * The two phases of inference are bound by different things, and both are measurable with
 * a few hundred milliseconds of arithmetic:
 *
 * - **Decode is memory-bandwidth-bound.** Generating one token reads essentially every
 *   weight once, so `tok/s ~= bandwidth / model_bytes`. That is a physical relationship,
 *   not a fitted curve, and it is why a Q8_0 model decodes slower than the same model in
 *   Q4_0 despite better kernel coverage.
 * - **Prefill is compute-bound.** It is a large GEMM over the whole prompt, so it tracks
 *   integer throughput on the performance cores rather than memory speed.
 *
 * So: measure bandwidth, measure integer throughput, and scale from one anchor point that
 * was measured properly on real hardware.
 *
 * ## The anchor
 *
 * Every constant below comes from one calibration run - CMF Phone 1 (Dimensity 7300,
 * 4x Cortex-A78 @ 2.5 GHz + 4x A55), Llama-3.2-1B-Instruct, 4 threads pinned to the
 * performance cluster, `llama-bench -p 512 -n 128 -r 3`, 2026-08-06:
 *
 * | quant | file      | prefill pp512 | decode tg128 |
 * |-------|-----------|--------------:|-------------:|
 * | Q4_0  | 773.0 MB  |   128.2 tok/s |   18.2 tok/s |
 * | Q8_0  | 1321.1 MB |   208.1 tok/s |   11.5 tok/s |
 *
 * Those two rows are what make the estimate honest about the Q4_0/Q8_0 trade instead of
 * treating "reaches KleidiAI" as a single boolean good thing. Full method and the
 * perplexity column: `docs/QUANTIZATION-QUALITY.md`.
 *
 * ## What this is not
 *
 * An estimate, and it says so on screen. It cannot see thermal headroom, memory pressure
 * from other apps, or the vendor's scheduler. Treat it as an order-of-magnitude guide that
 * keeps a user off a model their phone cannot serve - not as a benchmark result. The real
 * benchmark still ships in the app and in ENTITY Bench.
 */
object DeviceProbe {

    // ---------------------------------------------------------------- calibration anchor

    /** Decode bytes-per-second actually achieved on the anchor device: 0.773 GB x 18.2 tok/s. */
    private const val ANCHOR_DECODE_BYTES_PER_S = 773_025_920.0 * 18.2

    /**
     * Bandwidth this probe measures on the anchor device. Ratio to it scales the estimate.
     *
     * 26.2 GB/s, read off the anchor phone itself rather than assumed. Note how far this is
     * from the ~14 GB/s that decode actually achieves there (0.773 GB x 18.2 tok/s): a
     * linear `arraycopy` sees near-peak DRAM throughput, while decode walks many separate
     * tensors and pays for it. The gap is real and is exactly why this constant has to be
     * the probe's own reading on a known device instead of a datasheet figure - the ratio
     * cancels it out, the absolute number would not.
     */
    private const val ANCHOR_BANDWIDTH_GBS = 26.2

    /** Anchor prefill: 128.2 tok/s on 1.24B params, Q4_0. */
    private const val ANCHOR_PREFILL_TOKS = 128.2
    private const val ANCHOR_PREFILL_PARAMS_B = 1.24

    /** Integer throughput this probe measures on the anchor device, arbitrary units. */
    private const val ANCHOR_COMPUTE_SCORE = 1.0

    /**
     * Q8_0 prefill advantage over Q4_0, measured: 208.1 / 128.2. All of a Q8_0 model's block
     * matmuls land on KleidiAI's 8-bit kernel, which is the fastest prompt path on this
     * hardware even though the file is larger.
     */
    private const val Q8_PREFILL_GAIN = 1.62

    /**
     * K-quants reach no KleidiAI kernel at all. Measured on the anchor device, same model:
     * Q4_K_M prefills at 111.7 tok/s against Q4_0's 128.2, and Q3_K_L collapses to 43.4.
     */
    private const val KQUANT_PREFILL_PENALTY = 0.87

    // ---------------------------------------------------------------- profile

    data class Profile(
        /** Sustained copy bandwidth, GB/s. Predicts decode. */
        val bandwidthGBs: Double,
        /** Integer multiply-accumulate throughput relative to the anchor device. Predicts prefill. */
        val computeScore: Double,
        val perfCores: Int,
        /**
         * Memory the system reports as available at probe time, not installed RAM. This is
         * what a model has to fit into; installed RAM would recommend against capacity the
         * phone is already spending on everything else the user has open.
         */
        val availableRamBytes: Long,
        /** Installed RAM. Reported alongside the free figure so the gap is visible, never sized against. */
        val totalRamBytes: Long,
        val flags: Set<String>,
        /** Wall time the probe itself took, so the UI can be honest about how cheap it was. */
        val elapsedMs: Long,
    ) {
        val availableRamGb: Double get() = availableRamBytes / 1_073_741_824.0
        val totalRamGb: Double get() = totalRamBytes / 1_073_741_824.0
    }

    /** What the user is optimising for. Changes which quantization wins, not which model. */
    enum class Workload(val label: String, val blurb: String) {
        BALANCED("Balanced", "A mix of prompt length and reply length"),
        LONG_PROMPT("Fast first token", "Long prompts, short answers - summarising, Q&A over pasted text"),
        LONG_GENERATION("Fast typing", "Short prompts, long answers - drafting, brainstorming"),
    }

    data class Estimate(val prefillToksPerS: Double, val decodeToksPerS: Double) {
        /** Seconds to first token on a 512-token prompt, the number a user actually feels. */
        val ttftSeconds: Double get() = if (prefillToksPerS <= 0) 0.0 else 512.0 / prefillToksPerS
    }

    data class Recommendation(
        val entry: ModelCatalog.Entry,
        val estimate: Estimate,
        val headline: String,
        val why: String,
        val runnerUp: ModelCatalog.Entry?,
    )

    // ---------------------------------------------------------------- measurement

    /**
     * Runs the probe. Blocking, roughly 300-600 ms, no allocation beyond the two buffers.
     * Call off the main thread.
     */
    fun measure(
        availableRamBytes: Long,
        totalRamBytes: Long,
        perfCores: Int,
        flags: Set<String>,
    ): Profile {
        var bandwidth = 0.0
        var compute = 0.0
        val elapsed = measureNanoTime {
            bandwidth = measureBandwidthGBs()
            compute = measureComputeScore(max(1, perfCores))
        } / 1_000_000L
        return Profile(
            bandwidth, compute, perfCores, availableRamBytes, totalRamBytes, flags, elapsed,
        )
    }

    /**
     * Sustained large-buffer copy bandwidth. Buffers are 32 MB so they cannot sit in any
     * phone's last-level cache, which is what makes this measure DRAM rather than SRAM -
     * and DRAM is what decode is waiting on.
     */
    private fun measureBandwidthGBs(): Double {
        val n = 32 * 1024 * 1024
        val src = ByteArray(n)
        val dst = ByteArray(n)
        System.arraycopy(src, 0, dst, 0, n)          // warm up, fault the pages in
        var best = 0.0
        repeat(3) {
            val ns = measureNanoTime { System.arraycopy(src, 0, dst, 0, n) }
            if (ns > 0) {
                // A copy touches n bytes read plus n written.
                val gbs = (2.0 * n) / ns
                if (gbs > best) best = gbs
            }
        }
        return best
    }

    /**
     * Integer multiply-accumulate throughput across [threads] threads, normalised so the
     * anchor device scores ~1.0. Deliberately integer rather than float: the kernels that
     * matter here are int8 dot products, and a float benchmark would rank a phone with
     * strong FP and weak integer units far too highly.
     */
    private fun measureComputeScore(threads: Int): Double {
        val iterations = 4_000_000
        val results = LongArray(threads)
        val workers = (0 until threads).map { t ->
            Thread {
                var acc = 1L
                var i = 0
                val ns = measureNanoTime {
                    while (i < iterations) {
                        acc = acc * 31L + i          // one multiply, one add, dependency chain
                        i++
                    }
                }
                // Keep acc live so the JIT cannot delete the loop.
                results[t] = if (acc == Long.MIN_VALUE) -1L else ns
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val slowestNs = (results.maxOrNull() ?: 0L).toDouble()
        if (slowestNs <= 0.0) return ANCHOR_COMPUTE_SCORE
        val opsPerNs = (iterations.toDouble() * threads) / slowestNs
        // 1.042 ops/ns is what the anchor device actually produces on four A78s - measured by
        // running this probe on it, not estimated. An earlier 2.6 was a guess, and it made the
        // anchor score 0.40 instead of 1.0, deflating every prefill estimate by the same 2.5x.
        // The divisor has to come from this loop on that phone: it is a dependency-chained
        // scalar MAC, so its absolute rate says nothing about the device's peak integer
        // throughput, only about how one device compares to another under the same loop.
        return opsPerNs / 1.042
    }

    // ---------------------------------------------------------------- prediction (pure)

    /**
     * Estimated throughput for one catalog entry on a measured device. Pure - no Android,
     * no timing - so the mapping is unit-testable and can be argued with.
     */
    fun estimate(e: ModelCatalog.Entry, p: Profile): Estimate {
        // Decode: bytes-per-second the device can stream, divided by the bytes one token
        // must read. Scaled from the anchor so the constant absorbs everything this crude
        // copy test does not model (prefetchers, KV traffic, scheduler).
        val bandwidthRatio = if (ANCHOR_BANDWIDTH_GBS <= 0) 1.0 else p.bandwidthGBs / ANCHOR_BANDWIDTH_GBS
        val decode = (ANCHOR_DECODE_BYTES_PER_S * bandwidthRatio) / e.sizeBytes

        // Prefill: compute-bound, so it scales with integer throughput and falls off with
        // parameter count. Then the quantization's kernel path is applied on top.
        var prefill = ANCHOR_PREFILL_TOKS * p.computeScore *
            (ANCHOR_PREFILL_PARAMS_B / max(0.1, e.paramsB))
        prefill *= when {
            e.quant == "Q8_0" -> Q8_PREFILL_GAIN
            e.kleidiAccelerated -> 1.0
            else -> KQUANT_PREFILL_PENALTY
        }
        // No dotprod at all means no fast integer path anywhere, KleidiAI or ggml.
        if ("dotprod" !in p.flags) prefill *= 0.45

        return Estimate(max(0.1, prefill), max(0.05, decode))
    }

    /**
     * The model this phone should actually start with, and why.
     *
     * Ranking is deliberately not "biggest that fits". A model is only worth recommending
     * if it will still be usable: below [MIN_USABLE_DECODE] tok/s a reply arrives slower
     * than most people read, and parameter count stops buying anything the user will wait
     * for.
     */
    fun recommend(p: Profile, workload: Workload = Workload.BALANCED): Recommendation? {
        val viable = ModelCatalog.ALL
            .filter { ModelCatalog.assess(it, p.availableRamBytes, p.flags).fit != ModelCatalog.Fit.TOO_BIG }
            .map { it to estimate(it, p) }
            .filter { (_, est) -> est.decodeToksPerS >= MIN_USABLE_DECODE }

        if (viable.isEmpty()) return null

        val scored = viable.sortedByDescending { (e, est) -> score(e, est, workload, p) }
        val (best, bestEst) = scored.first()
        val runnerUp = scored.drop(1).firstOrNull { (e, _) -> e.id != best.id }?.first

        return Recommendation(
            entry = best,
            estimate = bestEst,
            headline = "%s %s".format(best.name, best.quant),
            why = explain(best, bestEst, workload),
            runnerUp = runnerUp,
        )
    }

    /**
     * Below this a reply arrives slower than most people read, and a larger model stops
     * being worth its extra parameters.
     *
     * Comfortable reading is roughly 4-5 words per second, which is 5-7 tokens. Set at 5.0
     * after walking the budget case by hand: at a 4.0 floor, a 4 GB phone with 4 GB/s of
     * bandwidth was being recommended a 3B model estimated at 4.6 tok/s - inside the floor,
     * and genuinely unusable. The whole point of this probe is to stop someone downloading
     * 1.8 GB to discover that.
     */
    const val MIN_USABLE_DECODE = 5.0

    /** Comfortable reading speed. Above it, extra decode buys diminishing returns. */
    private const val COMFORTABLE_DECODE = 12.0

    private fun score(e: ModelCatalog.Entry, est: Estimate, w: Workload, p: Profile): Double {
        // Capability, saturating at 4B so 7B does not simply always win where it fits.
        //
        // The 2.2 weight was set by replaying the ranking against the real catalog rather
        // than picked. At 1.5 a 4 GB phone was recommended SmolLM2 360M over Llama 1B:
        // both saturate the decode reward, so the tiny model won on prompt speed alone.
        // Being fast at a task you are too small to do is not a recommendation.
        var s = min(4.0, e.paramsB) * 2.2

        // Usability. Decode above comfortable reading speed is worth little more, so the
        // reward saturates rather than rewarding a tiny model for being fast at nothing.
        s += min(est.decodeToksPerS, COMFORTABLE_DECODE) * 0.35

        // Workload preference is a tie-break, deliberately weaker than capability. Both
        // terms are capped hard before weighting: a 360M model prefills at ~800 tok/s and
        // decodes at ~40, and uncapped either one alone outranks every model worth running.
        // Being fast at a task you are too small to do is not a recommendation.
        when (w) {
            Workload.LONG_PROMPT -> s += min(est.prefillToksPerS, 250.0) * 0.008
            Workload.LONG_GENERATION -> s += min(est.decodeToksPerS, 20.0) * 0.15
            Workload.BALANCED -> s += min(est.prefillToksPerS, 250.0) * 0.004
        }

        // A model that cannot reach Arm's kernels gives up the prompt path entirely.
        if (!e.kleidiAccelerated) s -= 0.8

        // Fitting is not the same as fitting comfortably. assess() marks an entry TIGHT
        // when it leaves little headroom, and a model with no headroom gets killed by the
        // OS mid-conversation - which is worse for a user than a smaller model would be.
        if (ModelCatalog.assess(e, p.availableRamBytes, p.flags).fit == ModelCatalog.Fit.TIGHT) s -= 1.5
        return s
    }

    private fun explain(e: ModelCatalog.Entry, est: Estimate, w: Workload): String {
        val ttft = "%.1fs".format(est.ttftSeconds)
        val dec = "%.0f tok/s".format(est.decodeToksPerS)
        val base = "Estimated ~$dec generation and ~$ttft to first token on a 512-token prompt"
        val quantNote = when {
            e.quant == "Q8_0" -> "Q8_0 puts every weight on Arm's kernels - fastest prompts, but the largest file, so generation is slower"
            e.kleidiAccelerated -> "Q4_0 reaches Arm's KleidiAI kernels and stays small, which is what keeps generation quick"
            else -> "${e.quant} misses KleidiAI, so prompt processing runs on generic kernels"
        }
        val workloadNote = when (w) {
            Workload.LONG_PROMPT -> "Chosen for time-to-first-token because you picked long prompts."
            Workload.LONG_GENERATION -> "Chosen for generation speed because you picked long replies."
            Workload.BALANCED -> "Chosen as the best balance of capability and speed for this phone."
        }
        return "$base. $quantNote. $workloadNote These are estimates from a 0.5-second probe, not a benchmark."
    }
}
