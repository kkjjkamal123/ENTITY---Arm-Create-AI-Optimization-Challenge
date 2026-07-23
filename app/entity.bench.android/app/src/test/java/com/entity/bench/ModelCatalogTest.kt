package com.entity.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private val gb = 1_073_741_824L

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
    fun `a 3B model does not fit a 4 GB phone`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-3b-q8_0" }
        assertEquals(ModelCatalog.Fit.TOO_BIG, ModelCatalog.assess(e, 4 * gb, setOf("dotprod")).fit)
    }

    @Test
    fun `the reference 1B Q4_0 is a great fit on a 6 GB phone and names the ISA`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_0" }
        val onI8mm = ModelCatalog.assess(e, 6 * gb, setOf("dotprod", "i8mm"))
        assertEquals(ModelCatalog.Fit.GREAT, onI8mm.fit)
        assertTrue(onI8mm.reason.contains("i8mm"))

        val onDotprod = ModelCatalog.assess(e, 6 * gb, setOf("dotprod"))
        assertTrue(onDotprod.reason.contains("dotprod"))
    }

    @Test
    fun `a K-quant is flagged as missing KleidiAI`() {
        val e = ModelCatalog.ALL.first { it.id == "llama3.2-1b-q4_k_m" }
        val a = ModelCatalog.assess(e, 6 * gb, setOf("dotprod", "i8mm"))
        assertTrue(a.reason.contains("misses KleidiAI"))
        // it still runs - it just does not reach Arm's kernels
        assertTrue(a.fit != ModelCatalog.Fit.TOO_BIG)
    }

    @Test
    fun `recommendation scales with device RAM`() {
        val small = ModelCatalog.recommended(4 * gb, setOf("dotprod"))
        val large = ModelCatalog.recommended(12 * gb, setOf("dotprod", "i8mm"))
        assertNotNull(small)
        assertNotNull(large)
        assertTrue("a roomy phone should be pointed at a bigger model",
            large!!.paramsB >= small!!.paramsB)
        assertTrue("a recommendation should reach Arm's kernels", large.kleidiAccelerated)
    }
}
