package com.arm.aichat.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage arithmetic for [GgufMetadata.TensorCensus].
 *
 * The fixtures are not invented. They are the tensor tables of real Llama-3.2-1B-Instruct
 * files measured on 2026-08-06, which is the point: a file named Q4_0 is not Q4_0
 * throughout, and the difference is large enough to change what the app should tell a user.
 */
class TensorCensusTest {

    private fun tensor(name: String, type: GgmlType, params: Long) =
        GgufMetadata.TensorInfo(name, type, listOf(params))

    private fun census(vararg t: GgufMetadata.TensorInfo): GgufMetadata.TensorCensus {
        val quantized = t.filterNot { it.type.isFullPrecision }
        return GgufMetadata.TensorCensus(
            eligibleParams = quantized.filter { it.type.kleidiAiAccelerated }.sumOf { it.params },
            ineligibleParams = quantized.filterNot { it.type.kleidiAiAccelerated }.sumOf { it.params },
            ineligible = quantized.filterNot { it.type.kleidiAiAccelerated }
                .sortedByDescending { it.params },
            typeCounts = t.groupingBy { it.type }.eachCount(),
        )
    }

    /**
     * bartowski's published Llama-3.2-1B-Instruct-Q4_0.gguf - the file ENTITY's catalog
     * ships. Nominally Q4_0; 24% of its quantized weights cannot reach KleidiAI.
     */
    @Test
    fun `catalog Q4_0 is only 76 percent KleidiAI eligible`() {
        val c = census(
            tensor("blk.weights", GgmlType.Q4_0, 939_524_096L),
            tensor("token_embd.weight", GgmlType.Q6_K, 262_668_288L),
            tensor("blk.0.ffn_down.weight", GgmlType.Q4_1, 16_777_216L),
            tensor("blk.1.ffn_down.weight", GgmlType.Q4_1, 16_777_216L),
            tensor("output_norm.weight", GgmlType.F32, 2_048L),
        )
        assertFalse("a file with Q6_K and Q4_1 tensors is not fully accelerated", c.fullyAccelerated)
        assertEquals(76, c.kleidiCoveragePercent)
        assertEquals(
            "the worst offender is the tied embedding, which is also the output projection",
            "token_embd.weight",
            c.ineligible.first().name,
        )
    }

    /** Q8_0: every quantized tensor is on a KleidiAI path. */
    @Test
    fun `Q8_0 is fully eligible`() {
        val c = census(
            tensor("blk.weights", GgmlType.Q8_0, 973_078_528L),
            tensor("token_embd.weight", GgmlType.Q8_0, 262_668_288L),
            tensor("output_norm.weight", GgmlType.F32, 2_048L),
        )
        assertTrue(c.fullyAccelerated)
        assertEquals(100, c.kleidiCoveragePercent)
        assertTrue(c.ineligible.isEmpty())
    }

    /** A K-quant reaches nothing, whatever backend variant ggml loaded. */
    @Test
    fun `K-quant has zero coverage`() {
        val c = census(
            tensor("blk.weights", GgmlType.Q4_K, 973_078_528L),
            tensor("token_embd.weight", GgmlType.Q6_K, 262_668_288L),
        )
        assertEquals(0, c.kleidiCoveragePercent)
        assertFalse(c.fullyAccelerated)
        assertEquals(0L, c.eligibleParams)
    }

    /**
     * F32 norms and biases are excluded from the denominator. They are never candidates
     * for a matmul kernel, so counting them would dilute the figure with parameters that
     * were never at stake.
     */
    @Test
    fun `full precision tensors do not dilute coverage`() {
        val c = census(
            tensor("blk.weights", GgmlType.Q4_0, 1_000_000L),
            tensor("norm.a", GgmlType.F32, 500_000L),
            tensor("norm.b", GgmlType.F32, 500_000L),
        )
        assertEquals(1_000_000L, c.quantizedParams)
        assertEquals(100, c.kleidiCoveragePercent)
    }

    /** A model with nothing quantized reaches no KleidiAI kernel either. */
    @Test
    fun `pure F32 model reports zero coverage rather than dividing by zero`() {
        val c = census(tensor("norm", GgmlType.F32, 2_048L))
        assertEquals(0L, c.quantizedParams)
        assertEquals(0f, c.kleidiCoverage, 0f)
        assertFalse(c.fullyAccelerated)
    }

    /** Type codes must match `enum ggml_type` or every census is silently wrong. */
    @Test
    fun `ggml type codes match upstream`() {
        assertEquals(GgmlType.Q4_0, GgmlType.fromCode(2))
        assertEquals(GgmlType.Q4_1, GgmlType.fromCode(3))
        assertEquals(GgmlType.Q8_0, GgmlType.fromCode(8))
        assertEquals(GgmlType.Q6_K, GgmlType.fromCode(14))
        assertEquals(GgmlType.UNKNOWN, GgmlType.fromCode(9999))
    }
}
