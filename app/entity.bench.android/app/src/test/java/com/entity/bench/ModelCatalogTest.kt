package com.entity.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val gb = 1_073_741_824L

    /**
     * assess() sizes against memory the system reports free, not installed RAM, so every
     * figure in this class is a free-memory figure. 2.3 GB is what a 6 GB phone typically
     * has left once the launcher and a few background apps are resident.
     */
    private val freeOnAnchor = 2304L * 1024 * 1024

    @Test
    fun `every catalog entry has a resolvable shape`() {
        for (e in ModelCatalog.ALL) {
            assertTrue("size must be known for resume verification", e.sizeBytes > 0)
            assertTrue(e.url.startsWith("https://"))
            assertTrue("url must end in the file it claims", e.url.endsWith(e.fileName))
            assertEquals(e.quant == "Q4_0" || e.quant == "Q8_0", e.kleidiAccelerated)
        }
        // ids are what persist a selection, so they cannot collide
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.id }.distinct().size)
    }

    @Test
    fun `a 3B Q8_0 does not fit when only 1_5 GB is free`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-3b-q8_0" }
        val free = 1536L * 1024 * 1024
        assertEquals(ModelCatalog.Fit.TOO_BIG, ModelCatalog.assess(e, free, setOf("dotprod")).fit)
    }

    @Test
    fun `the reference 1B Q4_0 is a great fit on a 6 GB phone and names the ISA`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_0" }
        val onI8mm = ModelCatalog.assess(e, freeOnAnchor, setOf("dotprod", "i8mm"))
        assertEquals(ModelCatalog.Fit.GREAT, onI8mm.fit)
        assertTrue(onI8mm.reason.contains("i8mm"))

        val onDotprod = ModelCatalog.assess(e, freeOnAnchor, setOf("dotprod"))
        assertTrue(onDotprod.reason.contains("dotprod"))
    }

    @Test
    fun `a K-quant is flagged as missing KleidiAI`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_k_m" }
        val a = ModelCatalog.assess(e, freeOnAnchor, setOf("dotprod", "i8mm"))
        assertTrue(a.reason.contains("misses KleidiAI"))
        // it still runs - it just does not reach Arm's kernels
        assertTrue(a.fit != ModelCatalog.Fit.TOO_BIG)
    }

    @Test
    fun `recommendation scales with free memory`() {
        val small = ModelCatalog.recommended(1536L * 1024 * 1024, setOf("dotprod"))
        val large = ModelCatalog.recommended(9 * gb, setOf("dotprod", "i8mm"))
        assertNotNull(small)
        assertNotNull(large)
        assertTrue("a phone with more free memory should be pointed at a bigger model",
            large!!.paramsB >= small!!.paramsB)
        assertTrue("a recommendation should reach Arm's kernels", large.kleidiAccelerated)
    }

    // Chat-app-only: the flags come from llama's system-info string rather than
    // /proc/cpuinfo, so the bridge onto assess()'s lower-case names needs its own cover.
    @Test
    fun `feature flags come off the system-info string in the case assess expects`() {
        val info = "AARCH64 = 1 | NEON = 1 | DOTPROD = 1 | MATMUL_INT8 = 1 | SVE = 0 |"
        val flags = ModelCatalog.featureFlags(info)
        assertTrue("dotprod" in flags)
        assertTrue("i8mm" in flags)          // ggml reports i8mm as MATMUL_INT8
        assertTrue("a 0 must not count as present", "sve" !in flags)

        // The catalog can be opened before any model is loaded, so no system info at all
        // must still assess rather than throw.
        val none = ModelCatalog.featureFlags("")
        assertTrue(none.isEmpty())
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_0" }
        assertTrue(ModelCatalog.assess(e, freeOnAnchor, none).reason.contains("no dotprod"))
    }

    /**
     * A failed memory query must not read as a large phone.
     *
     * ActivityManager returns 0 or a negative figure on some devices, and assess() used to
     * answer OK for every entry - skipping the size check entirely, so recommended()
     * ranked on parameter count alone and picked the largest 4.4 GB model for a device
     * whose free memory nobody could read.
     */
    @Test
    fun `unknown free memory is sized conservatively, not treated as unlimited`() {
        for (unknown in listOf(0L, -1L)) {
            val flags = setOf("dotprod")
            val biggest = ModelCatalog.ALL.maxByOrNull { it.sizeBytes }!!
            val verdict = ModelCatalog.assess(biggest, unknown, flags)
            assertEquals(
                "the largest model must not be offered when free memory is unreadable",
                ModelCatalog.Fit.TOO_BIG, verdict.fit,
            )
            assertTrue(
                "the reason must say the figure was unreadable, not invent one",
                verdict.reason.contains("could not be read"),
            )

            // Nothing is GREAT on a number nobody measured, but small models stay usable.
            val small = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_0" }
            assertEquals(ModelCatalog.Fit.TIGHT, ModelCatalog.assess(small, unknown, flags).fit)

            val pick = ModelCatalog.recommended(unknown, flags)
            assertNotNull("a conservative pick is still better than none", pick)
            assertTrue(
                "recommended ${pick!!.id} (${pick.sizeBytes} bytes) on unknown memory",
                pick.sizeBytes < biggest.sizeBytes,
            )
        }
    }
}
