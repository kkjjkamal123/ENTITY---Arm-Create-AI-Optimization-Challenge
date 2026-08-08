package com.arm.aichat.gguf

/**
 * Per-tensor GGML types, as written in the GGUF tensor-info table.
 *
 * Codes mirror `enum ggml_type` in `ggml/include/ggml.h`. Gaps in the numbering are
 * types that upstream removed (4/5 were Q4_2/Q4_3; 31-33 were the first IQ1 attempts).
 *
 * This is deliberately NOT the same thing as [FileType]. `general.file_type` is a single
 * key describing what the file was *nominally* quantized to; this enum describes what an
 * individual tensor actually *is*. On real files the two disagree, which is the entire
 * reason this type exists - see [GgufMetadata.TensorCensus].
 */
enum class GgmlType(val code: Int, val label: String) {
    F32(0, "F32"),
    F16(1, "F16"),
    Q4_0(2, "Q4_0"),
    Q4_1(3, "Q4_1"),
    Q5_0(6, "Q5_0"),
    Q5_1(7, "Q5_1"),
    Q8_0(8, "Q8_0"),
    Q8_1(9, "Q8_1"),

    /* K-quants ------------------------------------------------------------ */
    Q2_K(10, "Q2_K"),
    Q3_K(11, "Q3_K"),
    Q4_K(12, "Q4_K"),
    Q5_K(13, "Q5_K"),
    Q6_K(14, "Q6_K"),
    Q8_K(15, "Q8_K"),

    /* IQ quants ----------------------------------------------------------- */
    IQ2_XXS(16, "IQ2_XXS"),
    IQ2_XS(17, "IQ2_XS"),
    IQ3_XXS(18, "IQ3_XXS"),
    IQ1_S(19, "IQ1_S"),
    IQ4_NL(20, "IQ4_NL"),
    IQ3_S(21, "IQ3_S"),
    IQ2_S(22, "IQ2_S"),
    IQ4_XS(23, "IQ4_XS"),

    /* Integer / float scalars (used by non-weight tensors) ----------------- */
    I8(24, "I8"),
    I16(25, "I16"),
    I32(26, "I32"),
    I64(27, "I64"),
    F64(28, "F64"),

    IQ1_M(29, "IQ1_M"),
    BF16(30, "BF16"),
    TQ1_0(34, "TQ1_0"),
    TQ2_0(35, "TQ2_0"),

    UNKNOWN(-1, "unknown");

    /**
     * Whether Arm's KleidiAI registers a matmul kernel for this tensor type.
     *
     * KleidiAI covers exactly Q4_0 and Q8_0 (`ggml/src/ggml-cpu/kleidiai/kleidiai.cpp`).
     * A tensor of any other type falls back to generic ggml no matter which CPU backend
     * variant was loaded, so the i8mm/dotprod kernels the variant was built for are never
     * entered for that tensor.
     */
    val kleidiAiAccelerated: Boolean
        get() = this == Q4_0 || this == Q8_0

    /** True for the float types used by norms and biases, which are never quantized. */
    val isFullPrecision: Boolean
        get() = this == F32 || this == F64

    companion object {
        private val map = entries.associateBy(GgmlType::code)
        fun fromCode(code: Int): GgmlType = map[code] ?: UNKNOWN
    }
}
