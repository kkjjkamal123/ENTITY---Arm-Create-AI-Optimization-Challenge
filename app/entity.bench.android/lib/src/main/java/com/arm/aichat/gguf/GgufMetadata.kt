package com.arm.aichat.gguf

import java.io.IOException


/**
 * Structured metadata of GGUF
 */
data class GgufMetadata(
    // Basic file info
    val version: GgufVersion,
    val tensorCount: Long,
    val kvCount: Long,

    // General info
    val basic: BasicInfo,
    val author: AuthorInfo? = null,
    val additional: AdditionalInfo? = null,
    val architecture: ArchitectureInfo? = null,
    val baseModels: List<BaseModelInfo>? = null,
    val tokenizer: TokenizerInfo? = null,

    // Derivative info
    val dimensions: DimensionsInfo? = null,
    val attention: AttentionInfo? = null,
    val rope: RopeInfo? = null,
    val experts: ExpertsInfo? = null,

    /**
     * What the tensor table actually contains, as opposed to what `general.file_type`
     * claims. Null when the tensor-info section could not be read.
     */
    val tensors: TensorCensus? = null,
) {

    /**
     * Measured KleidiAI coverage, read from the GGUF tensor-info table.
     *
     * `general.file_type` is a single nominal label and it is routinely wrong about what
     * is inside the file. A `Llama-3.2-1B-Instruct-Q4_0.gguf` published by bartowski
     * carries `token_embd.weight` at Q6_K and two `ffn_down` tensors at Q4_1 - 24.0% of
     * its quantized weights, none of which KleidiAI can touch. Because Llama 3.2 ties its
     * embeddings, that Q6_K tensor is also the output projection, so the single largest
     * matmul in the model runs on generic ggml in a file chosen precisely because Q4_0
     * reaches Arm's kernels.
     *
     * Two tensors at Q4_1 come from `src/llama-quant.cpp`, which promotes the first
     * `n_layer/8` `ffn_down` layers whenever an importance matrix is supplied. So adding
     * an imatrix improves output quality and *reduces* KleidiAI coverage at the same
     * time, and neither effect is visible from the filename.
     *
     * This is the same per-tensor discipline as the upstream warning this project landed
     * in llama.cpp ([PR #25701](https://github.com/ggml-org/llama.cpp/pull/25701)), which
     * fires per node of a graph rather than per file.
     *
     * @param eligibleParams   parameters in tensors KleidiAI can accelerate (Q4_0/Q8_0)
     * @param ineligibleParams parameters in quantized tensors it cannot
     * @param ineligible       the offending tensors, largest first, for display
     */
    data class TensorCensus(
        val eligibleParams: Long,
        val ineligibleParams: Long,
        val ineligible: List<TensorInfo>,
        val typeCounts: Map<GgmlType, Int>,
    ) {
        /** Quantized parameters considered, i.e. excluding F32 norms and biases. */
        val quantizedParams: Long get() = eligibleParams + ineligibleParams

        /**
         * Fraction of quantized weight parameters that reach KleidiAI, 0f..1f.
         * Returns 0f for a file with no quantized tensors at all (a pure F16/F32 model),
         * because none of it reaches KleidiAI either.
         */
        val kleidiCoverage: Float
            get() = if (quantizedParams == 0L) 0f else eligibleParams.toFloat() / quantizedParams

        /** Whether every quantized weight is on a KleidiAI path. */
        val fullyAccelerated: Boolean get() = quantizedParams > 0L && ineligibleParams == 0L

        /** Rounded percentage for UI use, so callers do not each re-derive it. */
        val kleidiCoveragePercent: Int get() = Math.round(kleidiCoverage * 100f)
    }

    /** One row of the GGUF tensor-info table. */
    data class TensorInfo(
        val name: String,
        val type: GgmlType,
        val dims: List<Long>,
    ) {
        val params: Long get() = dims.fold(1L) { acc, d -> acc * d }
    }

    enum class GgufVersion(val code: Int, val label: String) {
        /** First public draft; little‑endian only, no alignment key. */
        LEGACY_V1(1, "Legacy v1"),

        /** Added split‑file support and some extra metadata keys. */
        EXTENDED_V2(2, "Extended v2"),

        /** Current spec: endian‑aware, mandatory alignment, fully validated. */
        VALIDATED_V3(3, "Validated v3");

        companion object {
            fun fromCode(code: Int): GgufVersion =
                entries.firstOrNull { it.code == code }
                    ?: throw IOException("Unknown GGUF version code $code")
        }

        override fun toString(): String = "$label (code=$code)"
    }

    data class BasicInfo(
        val uuid: String? = null,
        val name: String? = null,
        val nameLabel: String? = null,
        val sizeLabel: String? = null,  // Size label like "7B"
    )

    data class AuthorInfo(
        val organization: String? = null,
        val author: String? = null,
        val doi: String? = null,
        val url: String? = null,
        val repoUrl: String? = null,
        val license: String? = null,
        val licenseLink: String? = null,
    )

    data class AdditionalInfo(
        val type: String? = null,
        val description: String? = null,
        val tags: List<String>? = null,
        val languages: List<String>? = null,
    )

    data class ArchitectureInfo(
        val architecture: String? = null,
        val fileType: Int? = null,
        val vocabSize: Int? = null,
        val finetune: String? = null,
        val quantizationVersion: Int? = null,
    )

    data class BaseModelInfo(
        val name: String? = null,
        val author: String? = null,
        val version: String? = null,
        val organization: String? = null,
        val url: String? = null,
        val doi: String? = null,
        val uuid: String? = null,
        val repoUrl: String? = null,
    )

    data class TokenizerInfo(
        val model: String? = null,
        val bosTokenId: Int? = null,
        val eosTokenId: Int? = null,
        val unknownTokenId: Int? = null,
        val paddingTokenId: Int? = null,
        val addBosToken: Boolean? = null,
        val addEosToken: Boolean? = null,
        val chatTemplate: String? = null,
    )

    data class DimensionsInfo(
        val contextLength: Int? = null,
        val embeddingSize: Int? = null,
        val blockCount: Int? = null,
        val feedForwardSize: Int? = null,
    )

    data class AttentionInfo(
        val headCount: Int? = null,
        val headCountKv: Int? = null,
        val keyLength: Int? = null,
        val valueLength: Int? = null,
        val layerNormEpsilon: Float? = null,
        val layerNormRmsEpsilon: Float? = null,
    )

    data class RopeInfo(
        val frequencyBase: Float? = null,
        val dimensionCount: Int? = null,
        val scalingType: String? = null,
        val scalingFactor: Float? = null,
        val attnFactor: Float? = null,
        val originalContextLength: Int? = null,
        val finetuned: Boolean? = null,
    )

    data class ExpertsInfo(
        val count: Int? = null,
        val usedCount: Int? = null,
    )
}
