package com.entity.bench

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

    private const val TAG = "ResultStore"

    /**
     * Serialises every mutation of index.jsonl.
     *
     * [save] appends a line while [delete] does a read-filter-rewrite of the same file,
     * and the two used to run with nothing between them. A benchmark finishing at the
     * moment a user deletes an old entry would have delete() read the file before save()'s
     * append landed, then write back the filtered copy - silently dropping the just-
     * finished run from the history index while its JSON file sat on disk unreferenced.
     * A multi-minute benchmark is too expensive to lose to a lost update.
     *
     * Reads are held under the same lock so a listing can never observe a half-rewritten
     * index. The critical sections are file-sized, not benchmark-sized, so the contention
     * cost is nil.
     */
    private val indexLock = Any()

    private fun dir(ctx: Context) = File(ctx.filesDir, "results").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.jsonl")

    /**
     * Writes a finished benchmark to disk and returns its file.
     *
     * @throws IOException if the result itself could not be written - storage full, or a
     *   filesystem error. That is genuinely fatal to this result and the caller has to say
     *   so; it used to propagate uncaught out of the coroutine and take the app down,
     *   which lost the same data and told the user nothing.
     *
     * A failure to update index.jsonl is not fatal and does not throw: the result file is
     * the durable artifact and the index is a derived listing. Losing a line costs the run
     * its place in history, which is recoverable; losing the file is not.
     */
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
        synchronized(indexLock) {
            runCatching { indexFile(ctx).appendText(line.toString() + "\n") }
                .onFailure { Log.e(TAG, "result ${f.name} saved but not indexed", it) }
        }
        return f
    }

    fun summaries(ctx: Context): List<Summary> = synchronized(indexLock) {
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
        synchronized(indexLock) {
            val idx = indexFile(ctx)
            if (!idx.exists()) return
            runCatching {
                val kept = idx.readLines().filter { l ->
                    runCatching { JSONObject(l).optString("file") != fileName }.getOrDefault(true)
                }
                idx.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            }.onFailure { Log.e(TAG, "could not rewrite the index after deleting $fileName", it) }
        }
    }
}
