package com.arm.aichat.gguf

import kotlin.collections.get


/**
 * Numerical codes used by `general.file_type` (see llama.cpp repo's `constants.py`).
 * The `label` matches what llama‑cli prints.
 */
enum class FileType(val code: Int, val label: String) {
    ALL_F32(0, "all F32"),
    MOSTLY_F16(1, "F16"),
    MOSTLY_Q4_0(2, "Q4_0"),
    MOSTLY_Q4_1(3, "Q4_1"),
    // 4 removed
    MOSTLY_Q8_0(7, "Q8_0"),
    MOSTLY_Q5_0(8, "Q5_0"),
    MOSTLY_Q5_1(9, "Q5_1"),

    /* K‑quants ------------------------------------------------------------ */
    MOSTLY_Q2_K      (10, "Q2_K - Medium"),
    MOSTLY_Q3_K_S    (11, "Q3_K - Small"),
    MOSTLY_Q3_K_M    (12, "Q3_K - Medium"),
    MOSTLY_Q3_K_L    (13, "Q3_K - Large"),
    MOSTLY_Q4_K_S    (14, "Q4_K - Small"),
    MOSTLY_Q4_K_M    (15, "Q4_K - Medium"),
    MOSTLY_Q5_K_S    (16, "Q5_K - Small"),
    MOSTLY_Q5_K_M    (17, "Q5_K - Medium"),
    MOSTLY_Q6_K      (18, "Q6_K"),

    /* IQ quants ----------------------------------------------------------- */
    MOSTLY_IQ2_XXS   (19, "IQ2_XXS - 2.06 bpw"),
    MOSTLY_IQ2_XS    (20, "IQ2_XS - 2.31 bpw"),
    MOSTLY_Q2_K_S    (21, "Q2_K - Small"),
    MOSTLY_IQ3_XS    (22, "IQ3_XS - 3.30 bpw"),
    MOSTLY_IQ3_XXS   (23, "IQ3_XXS - 3.06 bpw"),
    MOSTLY_IQ1_S     (24, "IQ1_S - 1.56 bpw"),
    MOSTLY_IQ4_NL    (25, "IQ4_NL - 4.5 bpw"),
    MOSTLY_IQ3_S     (26, "IQ3_S - 3.44 bpw"),
    MOSTLY_IQ3_M     (27, "IQ3_M - 3.66 bpw"),
    MOSTLY_IQ2_S     (28, "IQ2_S - 2.50 bpw"),
    MOSTLY_IQ2_M     (29, "IQ2_M - 2.70 bpw"),
    MOSTLY_IQ4_XS    (30, "IQ4_XS - 4.25 bpw"),
    MOSTLY_IQ1_M     (31, "IQ1_M - 1.75 bpw"),

    /* BF16 & Ternary ------------------------------------------------------ */
    MOSTLY_BF16      (32, "BF16"),
    MOSTLY_TQ1_0     (36, "TQ1_0 - 1.69 bpw ternary"),
    MOSTLY_TQ2_0     (37, "TQ2_0 - 2.06 bpw ternary"),

    /* Special flag -------------------------------------------------------- */
    GUESSED(1024, "(guessed)"),

    UNKNOWN(-1, "unknown");

    /**
     * Whether Arm's KleidiAI kernels can accelerate this quantization.
     *
     * KleidiAI registers matmul kernels for exactly two GGML types, Q4_0 and Q8_0
     * (see `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp`). Every other type - including
     * the whole K-quant and IQ family - falls back to generic ggml kernels, so the
     * i8mm/dotprod paths the backend variant was built for are never entered.
     *
     * This matters most for prompt processing, which is a compute-bound GEMM.
     * Measured on a Dimensity 7300, Llama-3.2-1B, PP 512 on 4 threads:
     * Q4_0 (KleidiAI) 116 tok/s vs Q3_K_L (generic) 42.7 tok/s, so time-to-first-token
     * on a 512-token prompt drops from ~12.0s to ~4.4s. Decode is memory-bandwidth-bound
     * and is unaffected - it tracks bytes-per-weight, not kernel quality.
     */
    val kleidiAiAccelerated: Boolean
        get() = this == MOSTLY_Q4_0 || this == MOSTLY_Q8_0

    companion object {
        private val map = entries.associateBy(FileType::code)

        fun fromCode(code: Int?): FileType = map[code] ?: UNKNOWN
    }
}
