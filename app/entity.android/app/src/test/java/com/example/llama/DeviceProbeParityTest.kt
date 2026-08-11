package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the web port of [DeviceProbe] to this implementation.
 *
 * The project's site runs the same prediction in TypeScript so a reader can see what a
 * model will do on a phone without installing an APK on an Arm device first. That is only
 * worth doing while the two agree. Two implementations of one policy drift the day someone
 * retunes a weight here and forgets a web page also claims to be this policy - and a site
 * quoting the app's numbers after the app has moved on is worse than a site that never
 * quoted them.
 *
 * So the site generates `test/resources/probe-parity.tsv` from its port - every estimate,
 * every fit verdict and reason, every recommendation, over a grid of eight profiles - and
 * this test asserts Kotlin reproduces it. Editing either side without regenerating the
 * fixture fails here.
 *
 * When this test fails after a deliberate policy change, the fix is to re-run
 * `npm run build:parity` in the site repo and commit the diff. That diff is a readable
 * statement of what the change did to the app's advice, which is the second reason the
 * fixture exists.
 *
 * The grid is not a sample of typical phones. It is the awkward cases: no dotprod, free
 * memory straddling the TIGHT and TOO_BIG boundaries, and a device too slow for anything
 * in the catalog.
 */
class DeviceProbeParityTest {

    /**
     * Six decimals - more precision than any of these estimates deserves, and less than a
     * double carries. A policy difference moves a value far more than this; last-bit
     * rounding between two languages moves it far less.
     */
    private val tolerance = 1e-5

    private data class Row(val kind: String, val cols: List<String>)

    private fun load(): List<Row> {
        val stream = javaClass.classLoader?.getResourceAsStream("probe-parity.tsv")
        assertNotNull(
            "probe-parity.tsv is missing from test resources - regenerate it with " +
                "`npm run build:parity` in the entity-web repo",
            stream,
        )
        return stream!!.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> line.split("\t").let { Row(it[0], it.drop(1)) } }
                .toList()
        }
    }

    private fun profiles(rows: List<Row>): Map<String, DeviceProbe.Profile> =
        rows.filter { it.kind == "profile" }.associate { r ->
            val (id, bandwidth, compute, cores, ram, flags) = r.cols
            id to DeviceProbe.Profile(
                bandwidthGBs = bandwidth.toDouble(),
                computeScore = compute.toDouble(),
                perfCores = cores.toInt(),
                availableRamBytes = ram.toLong(),
                // Installed RAM is never read by estimate/assess/recommend - fit is judged
                // against free memory - so the fixture does not carry it and this value is
                // arbitrary. If that ever stops being true, this test stops being valid and
                // the fixture must grow a column.
                totalRamBytes = ram.toLong(),
                flags = if (flags == "-") emptySet() else flags.split(",").toSet(),
                elapsedMs = 0,
            )
        }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun entry(id: String) = ModelCatalog.ALL.first { it.id == id }

    /** Every per-model estimate and fit verdict, for every profile in the grid. */
    @Test
    fun `estimates and fit verdicts match the web port`() {
        val rows = load()
        val byId = profiles(rows)
        val estimates = rows.filter { it.kind == "estimate" }
        assertTrue("fixture carries no estimates", estimates.isNotEmpty())

        for (r in estimates) {
            val (profileId, modelId, prefill, decode, ttft, fit) = r.cols
            val reason = r.cols[6]
            val p = byId[profileId] ?: error("fixture references unknown profile $profileId")
            val e = entry(modelId)
            val est = DeviceProbe.estimate(e, p)
            val where = "$profileId / $modelId"

            assertEquals("$where: prefill", prefill.toDouble(), est.prefillToksPerS, tolerance)
            assertEquals("$where: decode", decode.toDouble(), est.decodeToksPerS, tolerance)
            assertEquals("$where: ttft", ttft.toDouble(), est.ttftSeconds, tolerance)

            val assessment = ModelCatalog.assess(e, p.availableRamBytes, p.flags)
            assertEquals("$where: fit", fit, assessment.fit.name)
            // The reason string is user-visible text, and it is also the cheapest available
            // proof that both sides took the same branch through assess().
            assertEquals("$where: reason", reason, assessment.reason)
        }
    }

    /** The recommendation itself, per workload - the output that actually reaches a user. */
    @Test
    fun `recommendations match the web port`() {
        val rows = load()
        val byId = profiles(rows)
        val recommendations = rows.filter { it.kind == "recommend" }
        assertTrue("fixture carries no recommendations", recommendations.isNotEmpty())

        for (r in recommendations) {
            val (profileId, workload, modelId, runnerUp, decode, prefill) = r.cols
            val p = byId[profileId] ?: error("fixture references unknown profile $profileId")
            val rec = DeviceProbe.recommend(p, DeviceProbe.Workload.valueOf(workload))
            val where = "$profileId / $workload"

            if (modelId == "-") {
                assertEquals("$where: expected no recommendation", null, rec)
                continue
            }
            assertNotNull("$where: expected $modelId, got no recommendation", rec)
            assertEquals("$where: recommended model", modelId, rec!!.entry.id)
            assertEquals("$where: runner-up", runnerUp, rec.runnerUp?.id ?: "-")
            assertEquals("$where: decode", decode.toDouble(), rec.estimate.decodeToksPerS, tolerance)
            assertEquals("$where: prefill", prefill.toDouble(), rec.estimate.prefillToksPerS, tolerance)
        }
    }

    /**
     * The fixture must cover the whole catalog on every profile, or a model could be
     * dropped from one side without any assertion noticing.
     */
    @Test
    fun `fixture covers every catalog entry on every profile`() {
        val rows = load()
        val profileIds = rows.filter { it.kind == "profile" }.map { it.cols[0] }
        assertTrue("expected several profiles, got ${profileIds.size}", profileIds.size >= 5)

        val covered = rows.filter { it.kind == "estimate" }.groupBy({ it.cols[0] }, { it.cols[1] })
        val catalogIds = ModelCatalog.ALL.map { it.id }.toSet()
        for (id in profileIds) {
            assertEquals(
                "profile $id does not cover the whole catalog - regenerate probe-parity.tsv",
                catalogIds,
                covered[id]?.toSet(),
            )
        }
    }
}
