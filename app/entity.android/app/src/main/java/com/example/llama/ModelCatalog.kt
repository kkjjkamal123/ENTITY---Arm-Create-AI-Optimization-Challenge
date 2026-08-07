package com.example.llama

// A small curated GGUF catalog so a phone can get a runnable model without hunting a
// model host by hand. Sizes are the exact Content-Length the host reports (verified
// 2026-07-22), which is what makes a resumed download verifiable offline: a finished
// file either matches the expected length or it is incomplete.
//
// The list leans Q4_0 / Q8_0 because those are the two types Arm's KleidiAI kernels
// accelerate. One K-quant is included on purpose - the fit note says plainly that it
// misses KleidiAI, which is the same advice the model card gives after loading.
//
// Kept deliberately identical to the ENTITY Bench copy (com.entity.bench.ModelCatalog).
// The two apps are separate Gradle builds with separate :lib modules, so there is no
// shared module to put this in; identical files that can be diffed beat a fork.
object ModelCatalog {

    data class Entry(
        val id: String,
        val name: String,
        val fileName: String,
        val url: String,
        val paramsB: Double,
        val quant: String,
        val sizeBytes: Long,
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

    val ALL: List<Entry> = listOf(
        Entry(
            "qwen2.5-0.5b-q8_0", "Qwen2.5 0.5B Instruct", "Qwen2.5-0.5B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q8_0.gguf",
            0.5, "Q8_0", 531_068_480L,
        ),
        Entry(
            "llama3.2-1b-q4_0", "Llama 3.2 1B Instruct", "Llama-3.2-1B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
            1.24, "Q4_0", 773_025_920L,
        ),
        Entry(
            "llama3.2-1b-q4_k_m", "Llama 3.2 1B Instruct", "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            1.24, "Q4_K_M", 807_694_464L,
        ),
        Entry(
            "qwen2.5-1.5b-q4_0", "Qwen2.5 1.5B Instruct", "Qwen2.5-1.5B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_0.gguf",
            1.54, "Q4_0", 937_535_744L,
        ),
        Entry(
            "llama3.2-1b-q8_0", "Llama 3.2 1B Instruct", "Llama-3.2-1B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf",
            1.24, "Q8_0", 1_321_083_008L,
        ),
        Entry(
            "llama3.2-3b-q4_0", "Llama 3.2 3B Instruct", "Llama-3.2-3B-Instruct-Q4_0.gguf",
            "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf",
            3.21, "Q4_0", 1_921_909_280L,
        ),
        Entry(
            "llama3.2-3b-q8_0", "Llama 3.2 3B Instruct", "Llama-3.2-3B-Instruct-Q8_0.gguf",
            "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q8_0.gguf",
            3.21, "Q8_0", 3_421_899_296L,
        ),
    )

    enum class Fit { GREAT, OK, TIGHT, TOO_BIG }

    data class Assessment(val fit: Fit, val reason: String)

    /**
     * Device-aware fit. Weights are mmap'd rather than read into the heap, but the KV
     * cache, the app and the OS still need room, so total RAM - not free RAM - sets the
     * ceiling: free RAM swings with whatever else is running and would make the same
     * phone report a different verdict minute to minute.
     *
     * [flags] is lower-case ISA feature names ("dotprod", "i8mm"); see [featureFlags].
     */
    fun assess(e: Entry, totalRamBytes: Long, flags: Set<String>): Assessment {
        val ramGb = totalRamBytes / 1_073_741_824.0
        val sizeGb = e.sizeBytes / 1_073_741_824.0
        if (ramGb <= 0) return Assessment(Fit.OK, "device RAM unknown")

        if (sizeGb > ramGb * 0.5) {
            return Assessment(
                Fit.TOO_BIG,
                "%.1f GB of weights on a %.0f GB phone leaves no room for the KV cache".format(sizeGb, ramGb),
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

        if (sizeGb > ramGb * 0.35) {
            notes += "leaves little headroom"
            return Assessment(Fit.TIGHT, notes.joinToString(" · "))
        }

        // Within budget the larger model is the more capable one, so a roomy phone should
        // be pointed at 3B rather than handed the same 1B a 4 GB phone gets.
        val budgetB = if (ramGb >= 10) 3.5 else if (ramGb >= 6) 2.0 else 1.3
        val fit = if (e.kleidiAccelerated && e.paramsB <= budgetB) Fit.GREAT else Fit.OK
        if (e.paramsB > budgetB) notes += "%.1fB will decode slowly on this CPU".format(e.paramsB)
        return Assessment(fit, notes.joinToString(" · "))
    }

    /** The single best starting model for this phone, or null when nothing fits. */
    fun recommended(totalRamBytes: Long, flags: Set<String>): Entry? =
        ALL.filter { assess(it, totalRamBytes, flags).fit != Fit.TOO_BIG }
            .maxByOrNull { e ->
                val a = assess(e, totalRamBytes, flags)
                var s = if (e.kleidiAccelerated) 3.0 else 0.0
                if (a.fit == Fit.GREAT) s += 3.0 else if (a.fit == Fit.TIGHT) s -= 2.0
                s + e.paramsB
            }

    /**
     * Bridges [DeviceOptimizer.cpuFeatures] - which names what the loaded ggml variant
     * actually uses ("i8mm", "dotprod", "SVE", "SME") - onto the lower-case set [assess]
     * expects. An empty system-info string yields an empty set, and [assess] degrades to
     * "no dotprod" rather than failing, so the catalog opens before any model is loaded.
     */
    fun featureFlags(systemInfo: String): Set<String> =
        DeviceOptimizer.cpuFeatures(systemInfo).map { it.lowercase() }.toSet()

    fun humanSize(bytes: Long): String =
        if (bytes >= 1_000_000_000L) "%.2f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
}
