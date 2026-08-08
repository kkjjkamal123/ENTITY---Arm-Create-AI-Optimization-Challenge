package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prediction half of [DeviceProbe]. Pure - no timing, no Android - so the mapping from
 * a measured device to a recommended model can be argued with in a test rather than only
 * observed on a phone.
 *
 * The anchor fixture is the reference device this project calibrated against: CMF Phone 1,
 * Dimensity 7300, 4x Cortex-A78, 6 GB, dotprod but no i8mm.
 */
class DeviceProbeTest {

    private val gb = 1_073_741_824L

    private fun anchor() = DeviceProbe.Profile(
        bandwidthGBs = 6.4,
        computeScore = 1.0,
        perfCores = 4,
        totalRamBytes = 6 * gb,
        flags = setOf("dotprod"),
        elapsedMs = 500,
    )

    private fun entry(id: String) = ModelCatalog.ALL.first { it.id == id }

    /**
     * The estimate must reproduce the measurement it was calibrated from. Llama-3.2-1B Q4_0
     * decoded at 18.2 tok/s on the anchor device; a mapping that cannot return roughly that
     * for the device it was fitted to is wrong before it is applied anywhere else.
     */
    @Test
    fun `reproduces the anchor measurement it was calibrated from`() {
        val est = DeviceProbe.estimate(entry("llama3.2-1b-q4_0"), anchor())
        assertEquals("decode should land near the measured 18.2 tok/s", 18.2, est.decodeToksPerS, 1.0)
        assertEquals("prefill should land near the measured 128 tok/s", 128.0, est.prefillToksPerS, 12.0)
    }

    /**
     * The Q4_0/Q8_0 trade is the whole reason both ship. Measured on the anchor: Q8_0
     * prefills 62% faster and decodes 37% slower. If the model loses that, the catalog
     * would be free to recommend either interchangeably, which is exactly the mistake this
     * work exists to prevent.
     */
    @Test
    fun `Q8_0 is faster to first token and slower to generate`() {
        val p = anchor()
        val q4 = DeviceProbe.estimate(entry("llama3.2-1b-q4_0"), p)
        val q8 = DeviceProbe.estimate(entry("llama3.2-1b-q8_0"), p)
        assertTrue("Q8_0 should prefill faster", q8.prefillToksPerS > q4.prefillToksPerS)
        assertTrue("Q8_0 should decode slower - it is 71% more bytes", q8.decodeToksPerS < q4.decodeToksPerS)
    }

    /** Decode is bandwidth-bound, so a bigger file on one device must decode slower. */
    @Test
    fun `decode falls as the file grows`() {
        val p = anchor()
        val small = DeviceProbe.estimate(entry("qwen2.5-0.5b-q4_0"), p)
        val large = DeviceProbe.estimate(entry("llama3.2-3b-q4_0"), p)
        assertTrue(small.decodeToksPerS > large.decodeToksPerS)
    }

    /** A K-quant reaches no KleidiAI kernel, so its prompt path is slower at equal size. */
    @Test
    fun `K-quant prefills slower than Q4_0 of the same model`() {
        val p = anchor()
        val q40 = DeviceProbe.estimate(entry("llama3.2-1b-q4_0"), p)
        val q4km = DeviceProbe.estimate(entry("llama3.2-1b-q4_k_m"), p)
        assertTrue(q4km.prefillToksPerS < q40.prefillToksPerS)
    }

    /** A phone with no dotprod has no fast integer path at all, KleidiAI or otherwise. */
    @Test
    fun `no dotprod collapses the prompt estimate`() {
        val withDot = DeviceProbe.estimate(entry("llama3.2-1b-q4_0"), anchor())
        val without = DeviceProbe.estimate(
            entry("llama3.2-1b-q4_0"), anchor().copy(flags = emptySet()),
        )
        assertTrue(without.prefillToksPerS < withDot.prefillToksPerS * 0.6)
    }

    /** A roomy, fast phone should be pointed at something bigger than a 4 GB one gets. */
    @Test
    fun `a flagship is recommended a larger model than a budget phone`() {
        val budget = DeviceProbe.recommend(
            anchor().copy(totalRamBytes = 4 * gb, bandwidthGBs = 4.0, computeScore = 0.6),
        )
        val flagship = DeviceProbe.recommend(
            anchor().copy(
                totalRamBytes = 16 * gb, bandwidthGBs = 24.0, computeScore = 2.4,
                flags = setOf("dotprod", "i8mm", "sve"),
            ),
        )
        assertNotNull(budget)
        assertNotNull(flagship)
        assertTrue(
            "a 16 GB flagship should not be handed the same model as a 4 GB phone",
            flagship!!.entry.paramsB > budget!!.entry.paramsB,
        )
    }

    /** Never recommend something that will not actually be usable once downloaded. */
    @Test
    fun `recommendation always clears the usability floor`() {
        for (ram in listOf(3, 4, 6, 8, 12, 16)) {
            val rec = DeviceProbe.recommend(anchor().copy(totalRamBytes = ram.toLong() * gb))
            rec?.let {
                assertTrue(
                    "recommended ${it.entry.id} at ${ram}GB decodes below the usable floor",
                    it.estimate.decodeToksPerS >= DeviceProbe.MIN_USABLE_DECODE,
                )
            }
        }
    }

    /** A device too slow for anything in the catalog gets told so, not handed a bad pick. */
    @Test
    fun `an unusably slow device gets no recommendation`() {
        val potato = anchor().copy(
            bandwidthGBs = 0.05, computeScore = 0.05, totalRamBytes = 2 * gb, flags = emptySet(),
        )
        assertNull(DeviceProbe.recommend(potato))
    }

    /** Workload preference changes the quantization, not the family. */
    @Test
    fun `asking for fast first token prefers the higher-precision quant`() {
        val p = anchor().copy(totalRamBytes = 12 * gb, bandwidthGBs = 18.0, computeScore = 2.0)
        val prompt = DeviceProbe.recommend(p, DeviceProbe.Workload.LONG_PROMPT)
        val generation = DeviceProbe.recommend(p, DeviceProbe.Workload.LONG_GENERATION)
        assertNotNull(prompt)
        assertNotNull(generation)
        assertTrue(
            "a generation-first pick should not decode slower than a prompt-first pick",
            generation!!.estimate.decodeToksPerS >= prompt!!.estimate.decodeToksPerS,
        )
    }

    /** Every catalog URL must match its filename, or a resumed download verifies nothing. */
    @Test
    fun `catalog urls end in the filename they claim`() {
        for (e in ModelCatalog.ALL) {
            assertTrue("${e.id}: url does not end in ${e.fileName}", e.url.endsWith(e.fileName))
            assertTrue("${e.id}: size looks wrong", e.sizeBytes > 100_000_000L)
            assertTrue("${e.id}: vendor missing", e.vendor.isNotBlank())
        }
    }

    /** The catalog has to span real hardware, not just the author's phone. */
    @Test
    fun `catalog covers multiple vendors and the whole size range`() {
        val vendors = ModelCatalog.ALL.map { it.vendor }.toSet()
        assertTrue("expected at least five vendors, got $vendors", vendors.size >= 5)
        assertTrue("expected something under 0.6B", ModelCatalog.ALL.any { it.paramsB < 0.6 })
        assertTrue("expected something at 7B for flagships", ModelCatalog.ALL.any { it.paramsB >= 7.0 })
    }
}
