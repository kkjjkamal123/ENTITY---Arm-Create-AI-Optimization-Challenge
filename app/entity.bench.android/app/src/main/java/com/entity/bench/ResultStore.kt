package com.entity.bench

import android.content.Context
import org.json.JSONObject
import java.io.File

// Every completed benchmark is written to files/results/<ts>.json the moment it
// finishes - there is no "save" button to forget. index.jsonl carries one summary
// line per result so the home screen and history list never parse full telemetry.
object ResultStore {

    data class Summary(
        val ts: Long,
        val file: String,
        val type: String,
        val model: String,
        val runs: Int,
        val durationMin: Int,
        val charging: Boolean,
        val naiveTg: Double,
        val threadsTg: Double,
        val autoTg: Double,
        val best: String = "",     // sweep only: the winning configuration
    ) {
        val deltaPct get() = if (naiveTg > 0) (autoTg / naiveTg - 1) * 100 else 0.0
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "results").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.jsonl")

    fun save(ctx: Context, r: BenchResult): File {
        val f = File(dir(ctx), "${r.ts}.json")
        f.writeText(r.toJson().toString())
        val line = JSONObject().apply {
            put("ts", r.ts)
            put("file", f.name)
            put("type", r.type)
            put("model", r.model)
            put("runs", r.runsPerArm)
            put("duration_min", r.durationMin)
            put("charging", r.charging)
            put("naive", stat(r.naive?.passes?.map { it.tg } ?: emptyList()).median)
            put("threads", stat(r.threadsOnly?.passes?.map { it.tg } ?: emptyList()).median)
            // A sweep has no naive/threads-only/optimized arms, so its headline number is
            // the fastest configuration it found; the list shows that instead of a delta.
            val bestSweep = if (r.type == BenchResult.TYPE_SWEEP) r.bestSweepArm() else null
            put("auto", stat((bestSweep ?: r.optimized)?.passes?.map { it.tg } ?: emptyList()).median)
            put("best", bestSweep?.label ?: "")
        }
        indexFile(ctx).appendText(line.toString() + "\n")
        return f
    }

    fun summaries(ctx: Context): List<Summary> {
        val idx = indexFile(ctx)
        if (!idx.exists()) return emptyList()
        return runCatching {
            idx.readLines()
                .mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
                .mapNotNull { o ->
                    val name = o.optString("file")
                    if (!File(dir(ctx), name).exists()) return@mapNotNull null
                    Summary(
                        ts = o.optLong("ts"),
                        file = name,
                        type = o.optString("type", BenchResult.TYPE_ABLATION),
                        model = o.optString("model"),
                        runs = o.optInt("runs"),
                        durationMin = o.optInt("duration_min"),
                        charging = o.optBoolean("charging"),
                        naiveTg = o.optDouble("naive", 0.0),
                        threadsTg = o.optDouble("threads", 0.0),
                        autoTg = o.optDouble("auto", 0.0),
                        best = o.optString("best", ""),
                    )
                }
                .sortedByDescending { it.ts }
        }.getOrDefault(emptyList())
    }

    fun load(ctx: Context, fileName: String): BenchResult? = runCatching {
        BenchResult.fromJson(JSONObject(File(dir(ctx), fileName).readText()))
    }.getOrNull()

    fun delete(ctx: Context, fileName: String) {
        File(dir(ctx), fileName).delete()
        val idx = indexFile(ctx)
        if (!idx.exists()) return
        runCatching {
            val kept = idx.readLines().filter { l ->
                runCatching { JSONObject(l).optString("file") != fileName }.getOrDefault(true)
            }
            idx.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        }
    }
}
