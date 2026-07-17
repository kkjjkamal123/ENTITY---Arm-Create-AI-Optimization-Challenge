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
    fun `threads clamped to max four`() {
        assertEquals(4, DeviceOptimizer.suggest(6).threads)
        assertEquals(4, DeviceOptimizer.suggest(8).threads)
    }

    @Test
    fun `threads clamped to min two`() {
        assertEquals(2, DeviceOptimizer.suggest(1).threads)
        assertEquals(2, DeviceOptimizer.suggest(0).threads)
    }

    @Test
    fun `threads always inside the native 2 to 4 clamp`() {
        for (cores in 0..16) {
            val t = DeviceOptimizer.suggest(cores).threads
            assertTrue("cores $cores -> $t", t in 2..4)
        }
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
