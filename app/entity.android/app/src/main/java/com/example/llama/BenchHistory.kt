package com.example.llama

import android.content.Context
import org.json.JSONObject
import java.io.File

// Every finished benchmark is written to files/bench/<ts>.{csv,txt} the moment it
// completes - there is no "save" button to forget, which is how the published runs
// used to get lost when the picker killed the activity.
//
// Two files per run, no serialized object graph: the CSV is the same per-pass export
// the screen already builds, and the .txt is the same summary the COPY button emits.
// Re-exporting later is a file copy, so nothing has to be re-derived from a result
// that no longer exists in memory. index.jsonl carries one summary line per run so
// the list never reads either file.
object BenchHistory {

    data class Summary(
        val ts: Long,
        val stem: String,
        val type: String,
        val model: String,
        val runs: Int,
        val durationMin: Int,
        val charging: Boolean,
        val naiveTg: Double,
        val autoTg: Double,
    ) {
        val deltaPct get() = if (naiveTg > 0) (autoTg / naiveTg - 1) * 100 else 0.0
    }

    const val TYPE_ABLATION = "ablation"
    const val TYPE_SUSTAINED = "sustained"

    private fun dir(ctx: Context) = File(ctx.filesDir, "bench").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.jsonl")

    fun csvFile(ctx: Context, stem: String) = File(dir(ctx), "$stem.csv")
    fun textFile(ctx: Context, stem: String) = File(dir(ctx), "$stem.txt")

    fun save(
        ctx: Context,
        type: String,
        model: String,
        runs: Int,
        durationMin: Int,
        charging: Boolean,
        naiveTg: Double,
        autoTg: Double,
        csv: String,
        text: String,
    ) = runCatching {
        val ts = System.currentTimeMillis()
        val stem = ts.toString()
        csvFile(ctx, stem).writeText(csv)
        textFile(ctx, stem).writeText(text)
        val line = JSONObject().apply {
            put("ts", ts)
            put("stem", stem)
            put("type", type)
            put("model", model)
            put("runs", runs)
            put("duration_min", durationMin)
            put("charging", charging)
            put("naive", naiveTg)
            put("auto", autoTg)
        }
        indexFile(ctx).appendText(line.toString() + "\n")
    }.isSuccess

    fun summaries(ctx: Context): List<Summary> {
        val idx = indexFile(ctx)
        if (!idx.exists()) return emptyList()
        return runCatching {
            idx.readLines()
                .mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
                .mapNotNull { o ->
                    val stem = o.optString("stem")
                    if (!csvFile(ctx, stem).exists()) return@mapNotNull null
                    Summary(
                        ts = o.optLong("ts"),
                        stem = stem,
                        type = o.optString("type", TYPE_ABLATION),
                        model = o.optString("model"),
                        runs = o.optInt("runs"),
                        durationMin = o.optInt("duration_min"),
                        charging = o.optBoolean("charging"),
                        naiveTg = o.optDouble("naive", 0.0),
                        autoTg = o.optDouble("auto", 0.0),
                    )
                }
                .sortedByDescending { it.ts }
        }.getOrDefault(emptyList())
    }

    fun text(ctx: Context, stem: String): String? =
        runCatching { textFile(ctx, stem).readText() }.getOrNull()

    fun csv(ctx: Context, stem: String): String? =
        runCatching { csvFile(ctx, stem).readText() }.getOrNull()

    fun delete(ctx: Context, stem: String) {
        csvFile(ctx, stem).delete()
        textFile(ctx, stem).delete()
        val idx = indexFile(ctx)
        if (!idx.exists()) return
        runCatching {
            val kept = idx.readLines().filter { l ->
                runCatching { JSONObject(l).optString("stem") != stem }.getOrDefault(true)
            }
            idx.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        }
    }

    fun clear(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }
}
