package com.entity.bench

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `top cluster is the reference phone's four performance cores`() {
        // Dimensity 7300: 4x 2.5 GHz A78 + 4x 2.0 GHz A55. 2.0 GHz is below the 2.25 GHz
        // (10%-of-max) threshold, so exactly the four A78s count. The 4+4 invariant.
        assertEquals(
            4,
            DeviceInfo.topClusterCoreCount(
                listOf(2000000L, 2000000L, 2000000L, 2000000L, 2500000L, 2500000L, 2500000L, 2500000L)
            )
        )
    }

    @Test
    fun `top cluster counts only cores within ten percent of the fastest`() {
        // 1 prime @3.0 + 3 perf @2.4 (below 2.7 threshold) -> only the prime counts, clamped up to MIN.
        assertEquals(2, DeviceInfo.topClusterCoreCount(listOf(3000000L, 2400000L, 2400000L, 2400000L)))
        // 6 perf @3.2 within 10% + 2 little @2.0 -> six, at the MAX clamp.
        assertEquals(6, DeviceInfo.topClusterCoreCount(listOf(3200000L, 3200000L, 3200000L, 3200000L, 3200000L, 3200000L, 2000000L, 2000000L)))
        // Uniform cores: all within 10%, clamped to MAX.
        assertEquals(6, DeviceInfo.topClusterCoreCount(List(8) { 2000000L }))
    }

    @Test
    fun `top cluster falls back to half the cores when cpufreq is unreadable`() {
        assertEquals(4, DeviceInfo.topClusterCoreCount(List(8) { 0L }))
        assertEquals(2, DeviceInfo.topClusterCoreCount(List(2) { 0L }))
    }

    @Test
    fun `fast core count excludes the little cluster`() {
        // 4 big at 2.4 GHz + 4 little at 1.8 GHz
        assertEquals(4, DeviceInfo.fastCoreCount(listOf(2400000L, 2400000L, 2400000L, 2400000L, 1800000L, 1800000L, 1800000L, 1800000L)))
        // 1 prime + 3 perf + 4 little
        assertEquals(4, DeviceInfo.fastCoreCount(listOf(3000000L, 2400000L, 2400000L, 2400000L, 1800000L, 1800000L, 1800000L, 1800000L)))
        // 2 big + 6 little
        assertEquals(2, DeviceInfo.fastCoreCount(listOf(2000000L, 2000000L, 1600000L, 1600000L, 1600000L, 1600000L, 1600000L, 1600000L)))
        // uniform cores: no little cluster to avoid
        assertEquals(4, DeviceInfo.fastCoreCount(List(4) { 2000000L }))
    }

    @Test
    fun `unreadable cpufreq falls back to half the cores`() {
        assertEquals(4, DeviceInfo.fastCoreCount(List(8) { 0L }))
        assertEquals(1, DeviceInfo.fastCoreCount(emptyList()))
    }

    @Test
    fun `fast core indices split big from little`() {
        assertEquals(
            listOf(4, 5, 6, 7),
            DeviceInfo.fastCoreIndices(listOf(2000000L, 2000000L, 2000000L, 2000000L, 2500000L, 2500000L, 2500000L, 2500000L))
        )
        // Uniform cores: everything counts as fast.
        assertEquals(listOf(0, 1, 2, 3), DeviceInfo.fastCoreIndices(List(4) { 2000000L }))
    }

    @Test
    fun `cpu features parsed from system info`() {
        // Exactly the shape llama_print_system_info() produces for the CPU backend.
        val i8mm = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | MATMUL_INT8 = 1 | DOTPROD = 1 | "
        assertEquals(listOf("i8mm", "dotprod"), DeviceInfo.cpuFeatures(i8mm))

        val sve = "CPU : NEON = 1 | MATMUL_INT8 = 1 | SVE = 1 | DOTPROD = 1 | SVE_CNT = 2 | SME = 1 | "
        assertEquals(listOf("SME", "SVE", "i8mm", "dotprod"), DeviceInfo.cpuFeatures(sve))

        // Pre-dotprod SoC, and the not-yet-initialised engine.
        assertEquals(emptyList<String>(), DeviceInfo.cpuFeatures("CPU : NEON = 1 | ARM_FMA = 1 | "))
        assertEquals(emptyList<String>(), DeviceInfo.cpuFeatures(""))
    }

    @Test
    fun `cpu flags parsed from proc cpuinfo Features line`() {
        // OPPO CPH2729 (Snapdragon 6 Gen 4): dotprod + i8mm, no fp16 vector token, no sve.
        val oppo = "CPU part\t: 0xd47\nFeatures\t: fp asimd aes asimddp sha512 i8mm bf16 dit\nCPU revision : 0\n"
        assertEquals(setOf("dotprod", "i8mm"), DeviceInfo.cpuFlags(oppo))

        // CMF Phone 1 (Dimensity 7300): dotprod only, no i8mm.
        val cmf = "Features\t: fp asimd evtstrm aes asimdhp asimddp\n"
        assertEquals(true, "dotprod" in DeviceInfo.cpuFlags(cmf))
        assertEquals(true, "fp16" in DeviceInfo.cpuFlags(cmf))
        assertEquals(false, "i8mm" in DeviceInfo.cpuFlags(cmf))

        // sve2 implies the sve token is present too.
        val v9 = "Features\t: fp asimd asimddp sve sve2 i8mm\n"
        assertEquals(setOf("dotprod", "sve", "sve2", "i8mm"), DeviceInfo.cpuFlags(v9))

        assertEquals(emptySet<String>(), DeviceInfo.cpuFlags(""))
    }
}
