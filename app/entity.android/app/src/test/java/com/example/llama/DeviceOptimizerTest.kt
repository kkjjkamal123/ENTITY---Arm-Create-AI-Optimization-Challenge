package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOptimizerTest {

    @Test
    fun `typical four fast cores`() {
        assertEquals(4, DeviceOptimizer.suggest(4).threads)
    }

    @Test
    fun `threads clamped to max six`() {
        assertEquals(6, DeviceOptimizer.suggest(6).threads)
        assertEquals(6, DeviceOptimizer.suggest(8).threads)
    }

    @Test
    fun `threads clamped to min two`() {
        assertEquals(2, DeviceOptimizer.suggest(1).threads)
        assertEquals(2, DeviceOptimizer.suggest(0).threads)
    }

    @Test
    fun `threads always inside the native 2 to 6 clamp`() {
        for (cores in 0..16) {
            val t = DeviceOptimizer.suggest(cores).threads
            assertTrue("cores $cores -> $t", t in 2..6)
        }
    }

    @Test
    fun `top cluster is the reference phone's four performance cores`() {
        // Dimensity 7300: 4x 2.5 GHz A78 + 4x 2.0 GHz A55. 2.0 GHz is below the 2.25 GHz
        // (10%-of-max) threshold, so exactly the four A78s count. The 4+4 invariant.
        assertEquals(
            4,
            DeviceOptimizer.topClusterCoreCount(
                listOf(2000000L, 2000000L, 2000000L, 2000000L, 2500000L, 2500000L, 2500000L, 2500000L)
            )
        )
    }

    @Test
    fun `top cluster counts only cores within ten percent of the fastest`() {
        // 1 prime @3.0 + 3 perf @2.4 (below 2.7 threshold) -> only the prime counts, clamped up to MIN.
        assertEquals(2, DeviceOptimizer.topClusterCoreCount(listOf(3000000L, 2400000L, 2400000L, 2400000L)))
        // 6 perf @3.2 within 10% + 2 little @2.0 -> six, at the MAX clamp.
        assertEquals(6, DeviceOptimizer.topClusterCoreCount(listOf(3200000L, 3200000L, 3200000L, 3200000L, 3200000L, 3200000L, 2000000L, 2000000L)))
        // Uniform cores: all within 10%, clamped to MAX.
        assertEquals(6, DeviceOptimizer.topClusterCoreCount(List(8) { 2000000L }))
        // Galaxy S26 Ultra: 2x 4.742 GHz prime + 6x 3.628 GHz. 3.628 is below the 4.268
        // threshold, so only the two prime cores count. Measured on SM-S948B, 2026-07-20 —
        // the case that exposed the benchmark's stale thread-count mirror.
        assertEquals(
            2,
            DeviceOptimizer.topClusterCoreCount(
                listOf(3628000L, 3628000L, 3628000L, 3628000L, 3628000L, 3628000L, 4742000L, 4742000L)
            )
        )
    }

    @Test
    fun `top cluster falls back to half the cores when cpufreq is unreadable`() {
        assertEquals(4, DeviceOptimizer.topClusterCoreCount(List(8) { 0L }))
        assertEquals(2, DeviceOptimizer.topClusterCoreCount(List(2) { 0L }))
    }

    @Test
    fun `context stays adaptive instead of a fixed number`() {
        val s = DeviceOptimizer.suggest(4)
        assertTrue(s.contextSummary.isNotBlank())
        assertTrue(s.contextSummary.contains("adaptive"))
        assertTrue(s.reason.contains("4 threads"))
    }

    @Test
    fun `fast core count excludes the little cluster`() {
        // 4 big at 2.4 GHz + 4 little at 1.8 GHz
        assertEquals(4, DeviceOptimizer.fastCoreCount(listOf(2400000L, 2400000L, 2400000L, 2400000L, 1800000L, 1800000L, 1800000L, 1800000L)))
        // 1 prime + 3 perf + 4 little
        assertEquals(4, DeviceOptimizer.fastCoreCount(listOf(3000000L, 2400000L, 2400000L, 2400000L, 1800000L, 1800000L, 1800000L, 1800000L)))
        // 2 big + 6 little
        assertEquals(2, DeviceOptimizer.fastCoreCount(listOf(2000000L, 2000000L, 1600000L, 1600000L, 1600000L, 1600000L, 1600000L, 1600000L)))
        // uniform cores: no little cluster to avoid
        assertEquals(4, DeviceOptimizer.fastCoreCount(List(4) { 2000000L }))
    }

    @Test
    fun `unreadable cpufreq falls back to half the cores`() {
        assertEquals(4, DeviceOptimizer.fastCoreCount(List(8) { 0L }))
        assertEquals(1, DeviceOptimizer.fastCoreCount(emptyList()))
    }

    @Test
    fun `cpu features parsed from system info`() {
        // Exactly the shape llama_print_system_info() produces for the CPU backend.
        val i8mm = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | MATMUL_INT8 = 1 | DOTPROD = 1 | "
        assertEquals(listOf("i8mm", "dotprod"), DeviceOptimizer.cpuFeatures(i8mm))

        val sve = "CPU : NEON = 1 | MATMUL_INT8 = 1 | SVE = 1 | DOTPROD = 1 | SVE_CNT = 2 | SME = 1 | "
        assertEquals(listOf("SME", "SVE", "i8mm", "dotprod"), DeviceOptimizer.cpuFeatures(sve))

        // Pre-dotprod SoC, and the not-yet-initialised engine.
        assertEquals(emptyList<String>(), DeviceOptimizer.cpuFeatures("CPU : NEON = 1 | ARM_FMA = 1 | "))
        assertEquals(emptyList<String>(), DeviceOptimizer.cpuFeatures(""))
    }
}
