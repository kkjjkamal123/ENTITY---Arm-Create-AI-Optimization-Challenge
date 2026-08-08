package com.entity.bench

// A small curated GGUF catalog so a phone can get a runnable model without hunting a
// model host by hand. Sizes are the exact Content-Length the host reports (verified
// 2026-07-22), which is what makes a resumed download verifiable offline: a finished
// file either matches the expected length or it is incomplete.
//
// The list leans Q4_0 / Q8_0 because those are the two types Arm's KleidiAI kernels
// accelerate. One K-quant is included on purpose - the fit note says plainly that it
// misses KleidiAI, which is the same advice the model card gives after loading.
//
// Kept deliberately identical to the ENTITY chat copy (com.example.llama.ModelCatalog).
// The two apps are separate Gradle builds with separate :lib modules, so there is no
// shared module to put this in; identical files that can be diffed beat a fork.
object ModelCatalog {

    /** What a model is built for. Drives grouping, not fit. */
    enum class Role(val label: String) {
        GENERAL("Chat"),
        CODING("Code"),
        REASONING("Reasoning"),
    }

    data class Entry(
        val id: String,
        val name: String,
        /** Who trained the base model. Shown so the catalog does not read as one vendor's list. */
        val vendor: String,
        val fileName: String,
        val url: String,
        val paramsB: Double,
        val quant: String,
        val sizeBytes: Long,
        val role: Role = Role.GENERAL,
    ) {
        /**
         * Expected KleidiAI eligibility, from the catalog's declared quantization.
         *
         * A prediction, not a measurement: a catalog row describes a file that has not been
         * downloaded yet, so there is no tensor table to count. KleidiAI ships kernels for
         * Q4_0 and Q8_0 only and everything else falls back, but a file labelled Q4_0 still
         * routinely holds tensors of other types - bartowski's Llama-3.2-1B Q4_0 has 24.0%
         * of its weights off the KleidiAI path, including the output projection.
         *
         * Once a model is on disk, the model info card reads
         * [com.arm.aichat.gguf.GgufMetadata.TensorCensus] and reports the measured
         * coverage instead. Treat this as "should reach Arm's kernels", not "does".
         */
        val kleidiAccelerated: Boolean get() = quant == "Q4_0" || quant == "Q8_0"
    }

    private const val HF = "https://huggingface.co"

    /**
     * Every `sizeBytes` below is the exact `Content-Length` the host reports, verified
     * 2026-08-06. That is what makes a resumed download verifiable offline: a finished file
     * either matches the expected length or it is incomplete.
     *
     * The list spans seven vendors and 360M to 7B parameters, because the same APK now runs
     * on 4 GB budget phones and on flagships with 16 GB. A catalog that only offers 1B
     * models wastes a Galaxy S26, and one that only offers 7B is useless on a CMF Phone 1.
     * [assess] decides which rows a given device should see.
     *
     * Quantization coverage is deliberately Q4_0 / Q8_0 first - the only two types Arm's
     * KleidiAI has kernels for - with Q4_K_M offered where quality matters more than prompt
     * speed, and as the only option for the two families that publish no Q4_0.
     *
     * The Q4_0 / Q8_0 pairing on the same model is not redundant. Measured on the reference
     * phone (Dimensity 7300, 4 threads pinned), Llama-3.2-1B:
     *
     * | quant | prefill pp512 | decode tg128 |
     * |-------|--------------:|-------------:|
     * | Q8_0  |   208.1 tok/s |   11.5 tok/s |
     * | Q4_0  |   128.2 tok/s |   18.2 tok/s |
     *
     * Q8_0 is 62% faster to first token and 37% slower to generate. Neither is "better";
     * they suit different workloads, which is why both ship. See
     * `docs/QUANTIZATION-QUALITY.md`.
     */
    val ALL: List<Entry> = listOf(
        // ---- 360M-500M: runs on anything, including pre-dotprod hardware -------------
        Entry(
            "smollm2-360m-q4_0", "SmolLM2 360M Instruct", "Hugging Face",
            "SmolLM2-360M-Instruct-Q4_0.gguf",
            "$HF/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_0.gguf",
            0.36, "Q4_0", 229_733_280L,
        ),
        Entry(
            "smollm2-360m-q8_0", "SmolLM2 360M Instruct", "Hugging Face",
            "SmolLM2-360M-Instruct-Q8_0.gguf",
            "$HF/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
            0.36, "Q8_0", 386_405_280L,
        ),
        Entry(
            "qwen2.5-0.5b-q4_0", "Qwen2.5 0.5B Instruct", "Alibaba",
            "Qwen2.5-0.5B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_0.gguf",
            0.5, "Q4_0", 352_972_352L,
        ),
        Entry(
            "qwen2.5-0.5b-q8_0", "Qwen2.5 0.5B Instruct", "Alibaba",
            "Qwen2.5-0.5B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q8_0.gguf",
            0.5, "Q8_0", 531_068_480L,
        ),

        // ---- 1B-2B: the mid-range sweet spot ----------------------------------------
        Entry(
            "llama3.2-1b-q4_0", "Llama 3.2 1B Instruct", "Meta",
            "Llama-3.2-1B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
            1.24, "Q4_0", 773_025_920L,
        ),
        Entry(
            "llama3.2-1b-q4_k_m", "Llama 3.2 1B Instruct", "Meta",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            1.24, "Q4_K_M", 807_694_464L,
        ),
        Entry(
            "llama3.2-1b-q8_0", "Llama 3.2 1B Instruct", "Meta",
            "Llama-3.2-1B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf",
            1.24, "Q8_0", 1_321_083_008L,
        ),
        Entry(
            "qwen2.5-1.5b-q4_0", "Qwen2.5 1.5B Instruct", "Alibaba",
            "Qwen2.5-1.5B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_0.gguf",
            1.54, "Q4_0", 937_535_744L,
        ),
        Entry(
            "qwen2.5-1.5b-q8_0", "Qwen2.5 1.5B Instruct", "Alibaba",
            "Qwen2.5-1.5B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q8_0.gguf",
            1.54, "Q8_0", 1_646_573_312L,
        ),
        Entry(
            "qwen2.5-coder-1.5b-q4_0", "Qwen2.5 Coder 1.5B", "Alibaba",
            "Qwen2.5-Coder-1.5B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_0.gguf",
            1.54, "Q4_0", 937_535_776L, Role.CODING,
        ),
        Entry(
            "deepseek-r1-qwen-1.5b-q4_0", "DeepSeek-R1 Distill Qwen 1.5B", "DeepSeek",
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf",
            "$HF/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf",
            1.78, "Q4_0", 1_068_807_776L, Role.REASONING,
        ),
        Entry(
            "smollm2-1.7b-q4_0", "SmolLM2 1.7B Instruct", "Hugging Face",
            "SmolLM2-1.7B-Instruct-Q4_0.gguf",
            "$HF/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_0.gguf",
            1.71, "Q4_0", 993_874_912L,
        ),
        // Google publishes no Q4_0 for Gemma 2, so this row misses KleidiAI by necessity
        // rather than by choice. assess() says so on the card instead of hiding it.
        Entry(
            "gemma2-2b-q4_k_m", "Gemma 2 2B Instruct", "Google",
            "gemma-2-2b-it-Q4_K_M.gguf",
            "$HF/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            2.61, "Q4_K_M", 1_708_582_752L,
        ),

        // ---- 3B: 8 GB phones and up --------------------------------------------------
        Entry(
            "qwen2.5-3b-q4_0", "Qwen2.5 3B Instruct", "Alibaba",
            "Qwen2.5-3B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_0.gguf",
            3.09, "Q4_0", 1_828_486_304L,
        ),
        Entry(
            "llama3.2-3b-q4_0", "Llama 3.2 3B Instruct", "Meta",
            "Llama-3.2-3B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf",
            3.21, "Q4_0", 1_921_909_280L,
        ),
        Entry(
            "llama3.2-3b-q8_0", "Llama 3.2 3B Instruct", "Meta",
            "Llama-3.2-3B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q8_0.gguf",
            3.21, "Q8_0", 3_421_899_296L,
        ),
        Entry(
            "phi3.5-mini-q4_0", "Phi-3.5 Mini Instruct", "Microsoft",
            "Phi-3.5-mini-instruct-Q4_0.gguf",
            "$HF/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_0.gguf",
            3.82, "Q4_0", 2_182_468_896L,
        ),

        // ---- 7B: flagships only ------------------------------------------------------
        Entry(
            "qwen2.5-7b-q4_0", "Qwen2.5 7B Instruct", "Alibaba",
            "Qwen2.5-7B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_0.gguf",
            7.62, "Q4_0", 4_444_121_792L,
        ),
        Entry(
            "mistral-7b-v0.3-q4_k_m", "Mistral 7B Instruct v0.3", "Mistral AI",
            "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            "$HF/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            7.25, "Q4_K_M", 4_372_812_000L,
        ),
    )

    enum class Fit { GREAT, OK, TIGHT, TOO_BIG }

    data class Assessment(val fit: Fit, val reason: String)

    /**
     * Device-aware fit, judged against memory the phone can actually hand out right now -
     * ActivityManager's availMem - rather than the RAM printed on the box.
     *
     * Total RAM systematically overstates what a model gets. A 6 GB phone with the launcher,
     * the browser and three background apps resident may have well under 2 GB to give, and a
     * rule written against 6 GB will happily recommend a model that then thrashes. What a
     * download has to fit into is what is free when it runs.
     *
     * The thresholds below are therefore fractions of *available* memory and are much less
     * conservative than fractions of total would be: a model whose weights are 70% of free
     * memory is flagged tight, not rejected. That is not a fudge - weights are mmap'd, so
     * they live in the page cache and the kernel can evict them under pressure instead of
     * failing an allocation. The KV cache, which is ordinary anonymous memory and cannot be
     * evicted, is what genuinely has to fit, and it is the smaller of the two.
     *
     * The cost of this choice is that the verdict moves with whatever else is running. That
     * is the honest answer: the same phone really does have less room when it is busy.
     *
     * [flags] is lower-case ISA feature names ("dotprod", "i8mm"); see [featureFlags].
     */
    fun assess(e: Entry, availableRamBytes: Long, flags: Set<String>): Assessment {
        val ramGb = availableRamBytes / 1_073_741_824.0
        val sizeGb = e.sizeBytes / 1_073_741_824.0
        if (ramGb <= 0) return Assessment(Fit.OK, "available memory unknown")

        if (sizeGb > ramGb) {
            return Assessment(
                Fit.TOO_BIG,
                "%.1f GB of weights with only %.1f GB free right now".format(sizeGb, ramGb),
            )
        }

        val notes = mutableListOf<String>()
        notes += if (e.kleidiAccelerated) {
            val isa = when {
                "i8mm" in flags -> "reaches KleidiAI, and this CPU has i8mm"
                "dotprod" in flags -> "reaches KleidiAI on this CPU's dotprod kernels"
                else -> "reaches KleidiAI, but this CPU has no dotprod"
            }
            "${e.quant} $isa"
        } else {
            "${e.quant} misses KleidiAI - runs ggml's Arm repack kernels instead"
        }

        if (sizeGb > ramGb * 0.7) {
            notes += "%.1f GB of weights against %.1f GB free leaves little headroom"
                .format(sizeGb, ramGb)
            return Assessment(Fit.TIGHT, notes.joinToString(" · "))
        }

        // Within budget the larger model is the more capable one, so a roomy phone should be
        // pointed at 3B rather than handed the same 1B a cramped one gets. These tiers are
        // free memory, not total, and so sit far below the numbers a spec sheet would
        // suggest: a 16 GB flagship with apps open commonly reports 6-8 GB available, which
        // is what puts it in the top tier here.
        val budgetB = when {
            ramGb >= 8 -> 8.0
            ramGb >= 5 -> 4.0
            ramGb >= 3 -> 2.0
            else -> 1.3
        }
        val fit = if (e.kleidiAccelerated && e.paramsB <= budgetB) Fit.GREAT else Fit.OK
        if (e.paramsB > budgetB) notes += "%.1fB will decode slowly on this CPU".format(e.paramsB)
        return Assessment(fit, notes.joinToString(" · "))
    }

    /**
     * The single best starting model for this phone, or null when nothing fits.
     *
     * Deliberately does NOT bias toward higher-precision quantizations on quality grounds,
     * even though the quality gap is large and measured - Q8_0 costs +0.09% perplexity
     * against F16 where Q4_0 costs +9.52% (`docs/QUANTIZATION-QUALITY.md`).
     *
     * The reason is bytes. Decode is memory-bandwidth-bound on this class of hardware, so
     * the same model in Q8_0 is 71% larger and decodes correspondingly slower. Measured on
     * the reference phone, promoting a single 262M-parameter tensor from Q6_K to Q8_0 -
     * 8% more file - cost 11.4% of decode throughput (17.99 to 15.94 tok/s). Extrapolating
     * that to a whole-model precision bump would trade away the thing this app exists to
     * deliver.
     *
     * Quality is surfaced to the user on the model card instead, where they can weigh it
     * themselves against a token rate they can see. It is not silently spent on their
     * behalf.
     */
    fun recommended(availableRamBytes: Long, flags: Set<String>): Entry? =
        ALL.filter { assess(it, availableRamBytes, flags).fit != Fit.TOO_BIG }
            .maxByOrNull { e ->
                val a = assess(e, availableRamBytes, flags)
                var s = if (e.kleidiAccelerated) 3.0 else 0.0
                if (a.fit == Fit.GREAT) s += 3.0 else if (a.fit == Fit.TIGHT) s -= 2.0
                s + e.paramsB
            }

    /**
     * Bridges [DeviceInfo.cpuFeatures] - which names what the loaded ggml variant actually
     * uses ("i8mm", "dotprod", "SVE", "SME") - onto the lower-case set [assess] expects. An
     * empty system-info string yields an empty set, and [assess] degrades to "no dotprod"
     * rather than failing, so the catalog opens before any model is loaded.
     *
     * The chat app's copy of this file calls `DeviceOptimizer.cpuFeatures`; the two apps
     * named the same helper differently before this file was shared. That one line is the
     * only intended difference between the copies.
     */
    fun featureFlags(systemInfo: String): Set<String> =
        DeviceInfo.cpuFeatures(systemInfo).map { it.lowercase() }.toSet()

    fun humanSize(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
}
