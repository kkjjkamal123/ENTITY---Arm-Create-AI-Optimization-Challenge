package com.entity.bench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Renders one SAVED result from disk. There is nothing to configure and nothing to
// re-run here - it is a record page, reachable any time from history.
class ResultActivity : AppCompatActivity() {

    private var result: BenchResult? = null
    private var fileName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        Insets.pad(findViewById(android.R.id.content))

        fileName = intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_copy).setOnClickListener { copyResult() }
        findViewById<View>(R.id.btn_export).setOnClickListener {
            exportCsv.launch("entity_bench_${result?.ts ?: System.currentTimeMillis()}.csv")
        }
        findViewById<View>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ResultStore.load(this@ResultActivity, fileName) }
            if (r == null) {
                Toast.makeText(this@ResultActivity, "Could not read this result.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            result = r
            render(r)
        }
    }

    private fun render(r: BenchResult) {
        findViewById<TextView>(R.id.result_date).text =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(r.ts))
        findViewById<TextView>(R.id.result_context).text =
            "${r.model} · ${r.deviceManufacturer} ${r.deviceModel} · app v${r.appVersion}"
        when (r.type) {
            BenchResult.TYPE_SUSTAINED -> renderSustained(r)
            BenchResult.TYPE_SWEEP -> renderSweep(r)
            else -> renderAblation(r)
        }
    }

    // ---- ablation ----

    private fun renderAblation(r: BenchResult) {
        val arms = r.ablationArms
        if (arms.size < 3) return
        fun stats(sel: (Pass) -> Double) = arms.map { a -> stat(a.passes.map(sel)) }
        val pp = stats { it.pp }
        val tg = stats { it.tg }
        val watts = stats { it.watts }
        val eff = stats { it.tokPerW }
        val ttft = stats { it.ttftMs }
        val temp = stats { it.startTempC }
        val cpu = stats { it.averageProcessCpuPercent }
        val free = stats { it.minimumFreeGb }
        val peakTemp = stats { it.peakBatteryTempC }
        val fastFreq = stats { it.meanFastCoreFreqMhz(r.fastCores) }
        val littleFreq = stats { it.meanLittleCoreFreqMhz(r.littleCores) }
        val powerValid = r.powerValid

        val headline = findViewById<TextView>(R.id.headline)
        headline.text = buildString {
            append("DECODE ${BenchExport.signed(r.decodeDeltaPct())} VS NAIVE")
            if (powerValid && eff[0].median > 0) {
                append("\n${"%.1f".format(eff[2].median / eff[0].median)}X MORE EFFICIENT")
            }
        }
        val naiveTg = tg[0].median
        val threadsTg = tg[1].median
        val optTg = tg[2].median
        findViewById<TextView>(R.id.headline_sub).text =
            if (naiveTg > 0 && threadsTg > 0)
                "Dropping to ${r.threadsOnly?.threads} threads alone is ${BenchExport.signed((threadsTg / naiveTg - 1) * 100)} over naive; " +
                "pinning those threads to the performance cores adds ${BenchExport.signed((optTg / threadsTg - 1) * 100)} on top."
            else ""

        val bars = findViewById<LinearLayout>(R.id.result_bars)
        bars.removeAllViews()
        val mx = maxOf(naiveTg, threadsTg, optTg, 0.001)
        Ui.bar(this, bars, "naive", naiveTg, mx, emphasize = false)
        Ui.bar(this, bars, "threads", threadsTg, mx, emphasize = false)
        Ui.bar(this, bars, "auto", optTg, mx, emphasize = true)

        val table = findViewById<LinearLayout>(R.id.table)
        table.removeAllViews()
        addRow(table, "",
            "NAIVE\n${r.naive?.threads} thr all cores",
            "THREADS\n${r.threadsOnly?.threads} thr no pin",
            "AUTO\n${r.optimized?.threads} thr pinned",
            "D%", header = true)
        fun cells(s: List<Stat>) = s.map { BenchExport.cellStat(it) }
        addRow(table, "Prompt t/s", cells(pp)[0], cells(pp)[1], cells(pp)[2], BenchExport.pct(pp[0].median, pp[2].median))
        addRow(table, "Decode t/s", cells(tg)[0], cells(tg)[1], cells(tg)[2], BenchExport.pct(tg[0].median, tg[2].median), emphasize = true)
        addRow(table, "TTFT* ms", cells(ttft)[0], cells(ttft)[1], cells(ttft)[2], BenchExport.pct(ttft[0].median, ttft[2].median))
        if (powerValid) {
            addRow(table, "Power W", cells(watts)[0], cells(watts)[1], cells(watts)[2], "")
            addRow(table, "Effcy tok/W", cells(eff)[0], cells(eff)[1], cells(eff)[2], BenchExport.pct(eff[0].median, eff[2].median))
        } else {
            addRow(table, "Power W", "-", "-", "-", "")
        }
        addRow(table, "App CPU %", cells(cpu)[0], cells(cpu)[1], cells(cpu)[2], "")
        if (fastFreq.any { it.n > 0 }) addRow(table, "Perf clk MHz", cells(fastFreq)[0], cells(fastFreq)[1], cells(fastFreq)[2], "")
        if (littleFreq.any { it.n > 0 }) addRow(table, "Lit. clk MHz", cells(littleFreq)[0], cells(littleFreq)[1], cells(littleFreq)[2], "")
        addRow(table, "Free RAM GB", cells(free)[0], cells(free)[1], cells(free)[2], "")
        addRow(table, "Start C", cells(temp)[0], cells(temp)[1], cells(temp)[2], "")
        addRow(table, "Peak batt C", cells(peakTemp)[0], cells(peakTemp)[1], cells(peakTemp)[2], "")
        addRow(table, "Peak thermal",
            thermalSummary(r.naive), thermalSummary(r.threadsOnly), thermalSummary(r.optimized), "")

        r.efficiency?.let { e ->
            val eDec = stat(e.passes.map { it.tg })
            val eW = stat(e.passes.map { it.watts })
            val eEff = stat(e.passes.map { it.tokPerW })
            addRow(table, "EFFICIENCY CORES",
                "AUTO\n${r.optimized?.threads} thr perf", "SLOW\n${e.threads} thr little", "", "D%", header = true)
            addRow(table, "Decode t/s", BenchExport.cellStat(tg[2]), BenchExport.cellStat(eDec), "", BenchExport.pct(tg[2].median, eDec.median))
            if (powerValid) {
                addRow(table, "Power W", BenchExport.cellStat(watts[2]), BenchExport.cellStat(eW), "", "")
                addRow(table, "Effcy tok/W", BenchExport.cellStat(eff[2]), BenchExport.cellStat(eEff), "", BenchExport.pct(eff[2].median, eEff.median))
            }
            addRow(table, "Peak thermal", thermalSummary(r.optimized), thermalSummary(e), "", "")
        }

        findViewById<TextView>(R.id.notes).text = ablationNotes(r, powerValid)
    }

    private fun ablationNotes(r: BenchResult, powerValid: Boolean): String {
        val n = r.runsPerArm
        val note = StringBuilder(
            "Synthetic llama-bench test (PP ${BenchRunner.PP} / TG ${BenchRunner.TG}), $n run${if (n > 1) "s" else ""} per arm - " +
            "median${if (n > 1) " ±σ (population)" else ""} across runs. Cooldown before every pass: at least ${BenchRunner.MIN_PAUSE_MS / 1000}s, " +
            "then up to ${BenchRunner.MAX_COOLDOWN_MS / 1000}s until the battery returns to within ${"%.1f".format(BenchRunner.COOL_MARGIN_C)}C of its pre-benchmark temperature " +
            "(never waiting below ${"%.1f".format(BenchRunner.MIN_COOL_TARGET_C)}C).\n\n" +
            "*TTFT is derived from each run's measured rates (${BenchRunner.PP}-token prompt eval + one decode step), not a live chat measurement. " +
            "Numbers are comparable across apps, and higher than live chat speed because the KV cache is minimal.\n\n" +
            "naive = ${r.naive?.threads} threads spread across all cores. threads = the same thread count auto picks, affinity off " +
            "(an upstream llama.cpp -t N run). auto = the shipped configuration, pinned to the performance cores. " +
            "D% compares auto with naive. Both phases run on the same thread count in every arm, so " +
            "naive -> threads isolates the thread count and threads -> auto isolates the core placement."
        )
        r.efficiency?.let { e ->
            note.append("\n\nEfficiency cores: a fourth arm pinning ${e.threads} threads to the slowest cluster, " +
                "same placement logic inverted. It asks whether the little cores are more energy-efficient (tok/W) " +
                "for decode or only slower - read the tok/W D%, not the decode D%. Valid only unplugged.")
        }
        val peakThermal = r.arms.maxOf { a -> a.passes.maxOfOrNull { it.peakThermalStatus } ?: 0 }
        if (peakThermal >= 2) {
            note.append("\n\nThermal: Android reached ${BenchExport.thermalLabel(peakThermal)} during this run.")
        }
        if (!powerValid) {
            note.append("\n\nPhone was CHARGING - power and tok/W need it unplugged to be valid, so they are hidden. " +
                "Speed numbers are still valid.")
        }
        return note.toString()
    }

    // ---- sustained ----

    private fun renderSustained(r: BenchResult) {
        val to = r.threadsOnly ?: return
        val opt = r.optimized ?: return
        fun dropPct(series: List<Double>): Double {
            val first = series.firstOrNull { it > 0 } ?: return 0.0
            val last = series.lastOrNull { it > 0 } ?: return 0.0
            return if (first > 0) (last / first - 1) * 100 else 0.0
        }
        val toTg = to.passes.map { it.tg }
        val optTg = opt.passes.map { it.tg }

        findViewById<TextView>(R.id.headline).text = "SUSTAINED ${r.durationMin} MIN\nNO COOLDOWN"
        findViewById<TextView>(R.id.headline_sub).text =
            "threads-only decode ${BenchExport.signed(dropPct(toTg))} from pass 1 to ${to.passes.size} · " +
            "auto ${BenchExport.signed(dropPct(optTg))} from pass 1 to ${opt.passes.size}"

        val bars = findViewById<LinearLayout>(R.id.result_bars)
        bars.removeAllViews()
        val mx = maxOf(stat(toTg).median, stat(optTg).median, 0.001)
        Ui.bar(this, bars, "threads", stat(toTg).median, mx, emphasize = false)
        Ui.bar(this, bars, "auto", stat(optTg).median, mx, emphasize = true)

        val table = findViewById<LinearLayout>(R.id.table)
        table.removeAllViews()
        addRow(table, "PASS", "THREADS\ntg/s · thermal", "AUTO\ntg/s · thermal", "", "", header = true)
        for (i in 0 until maxOf(to.passes.size, opt.passes.size)) {
            val a = to.passes.getOrNull(i)
            val b = opt.passes.getOrNull(i)
            addRow(table, "#${i + 1}",
                a?.let { "${BenchExport.fmt(it.tg)} · ${BenchExport.thermalLabel(it.peakThermalStatus)}" } ?: "-",
                b?.let { "${BenchExport.fmt(it.tg)} · ${BenchExport.thermalLabel(it.peakThermalStatus)}" } ?: "-",
                "", "")
        }

        findViewById<TextView>(R.id.notes).text =
            "${r.durationMin} min of back-to-back PP ${BenchRunner.PP} / TG ${BenchRunner.TG} passes per arm, only a fixed " +
            "${BenchRunner.SUSTAINED_GAP_MS / 1000}s gap between passes instead of a cooldown to baseline - heat is meant to " +
            "accumulate inside a block. Both arms start their block from the same cooled baseline, so pass 1 is comparable " +
            "across arms, but this is a single session: run it more than once, and swap which arm goes first, before " +
            "trusting the direction of any gap. n=1 per pass - read the trend across passes, not any single one."
    }

    // ---- sweep ----

    private fun renderSweep(r: BenchResult) {
        val arms = r.sweepArms
        if (arms.isEmpty()) return
        val best = r.bestSweepArm() ?: return
        val bestTg = stat(best.passes.map { it.tg }).median
        // What Auto would have picked on this phone: its derived width, pinned.
        val shipped = r.sweepArmFor(r.autoThreads, pinned = true)
        val shippedTg = shipped?.let { stat(it.passes.map { p -> p.tg }).median } ?: 0.0

        findViewById<TextView>(R.id.headline).text =
            "BEST: ${best.threads} THREADS\n${if (best.pinned) "PINNED" else "NO PIN"}"
        findViewById<TextView>(R.id.headline_sub).text = when {
            shipped == null -> "${BenchExport.fmt(bestTg)} tok/s decode measured across ${arms.size} configurations."
            best.key == shipped.key ->
                "Auto already picks this: ${r.autoThreads} threads pinned is the fastest of the " +
                "${arms.size} configurations measured, at ${BenchExport.fmt(bestTg)} tok/s decode."
            else ->
                "Auto picks ${r.autoThreads} threads pinned (${BenchExport.fmt(shippedTg)} tok/s), but " +
                "${best.threads} threads ${if (best.pinned) "pinned" else "unpinned"} measured " +
                "${BenchExport.signed((bestTg / shippedTg - 1) * 100)} faster at ${BenchExport.fmt(bestTg)} tok/s. " +
                "Set threads manually in the chat app to use it."
        }

        val bars = findViewById<LinearLayout>(R.id.result_bars)
        bars.removeAllViews()
        // One bar per width showing that width's better placement, so the chart stays
        // readable and the winner is always on it even when it is an unpinned arm.
        val mx = arms.maxOf { stat(it.passes.map { p -> p.tg }).median }.coerceAtLeast(0.001)
        for ((threads, group) in arms.groupBy { it.threads }.toSortedMap()) {
            val top = group.maxByOrNull { stat(it.passes.map { p -> p.tg }).median } ?: continue
            Ui.bar(this, bars, "${threads}t ${if (top.pinned) "pin" else "free"}",
                stat(top.passes.map { it.tg }).median, mx, emphasize = top.key == best.key)
        }

        val table = findViewById<LinearLayout>(R.id.table)
        table.removeAllViews()
        addRow(table, "THR", "PLACE", "DECODE", "PROMPT", "tok/W", header = true)
        for (a in arms) {
            val tg = stat(a.passes.map { it.tg })
            val pp = stat(a.passes.map { it.pp })
            val eff = stat(a.passes.map { it.tokPerW })
            addRow(table,
                "${a.threads}",
                if (a.pinned) "pinned" else "no pin",
                BenchExport.cellStat(tg),
                BenchExport.cellStat(pp),
                if (r.powerValid) BenchExport.cellStat(eff) else "-",
                emphasize = a.key == best.key)
        }

        findViewById<TextView>(R.id.notes).text =
            "Every thread width this device can use, each one pinned to that many of its fastest cores and again " +
            "left to the scheduler. ${r.runsPerArm} runs per configuration, median shown, full cooldown before " +
            "every pass. Pinning an explicit width masks to exactly that many fast cores, so a pinned/no-pin pair " +
            "at one width isolates placement while the column isolates width. Best is chosen on decode, which is " +
            "what a chat user waits on token by token - if you care more about a long first prompt, read the " +
            "prompt column instead, and if you care about battery, read tok/W. One device, one model, one " +
            "quantization: the answer here is this phone's, not a universal one."
    }

    // ---- table plumbing ----

    private fun thermalSummary(a: Arm?): String =
        BenchExport.thermalLabel(a?.passes?.maxOfOrNull { it.peakThermalStatus } ?: 0)

    private fun addRow(
        table: LinearLayout, metric: String,
        c1: String, c2: String, c3: String, c4: String,
        header: Boolean = false, emphasize: Boolean = false,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Ui.dp(this@ResultActivity, 6), Ui.dp(this@ResultActivity, 8),
                Ui.dp(this@ResultActivity, 6), Ui.dp(this@ResultActivity, 8))
        }
        fun cell(text: String, widthDp: Int, bold: Boolean, gravity: Int) {
            row.addView(TextView(this).apply {
                this.text = text
                setTextColor(getColor(R.color.mono_fg))
                textSize = 10.5f
                this.gravity = gravity
                typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(Ui.dp(this@ResultActivity, widthDp),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        cell(metric, 108, header || emphasize, Gravity.START)
        cell(c1, 82, header, Gravity.END)
        cell(c2, 84, header, Gravity.END)
        cell(c3, 84, header || emphasize, Gravity.END)
        cell(c4, 56, true, Gravity.END)
        table.addView(row)
        if (header) {
            table.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_fill)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@ResultActivity, 2))
            })
        }
    }

    // ---- actions ----

    private fun copyResult() {
        val r = result ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ENTITY Bench result", BenchExport.copyText(r)))
        Toast.makeText(this, R.string.result_copied, Toast.LENGTH_SHORT).show()
    }

    // The result lives on disk, so the export can always be rebuilt - even if this
    // activity was killed while the system file picker was in the foreground.
    private val exportCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val r = result ?: ResultStore.load(this@ResultActivity, fileName) ?: return@runCatching false
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(BenchExport.csv(r).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(this@ResultActivity,
                if (ok) "CSV exported" else "CSV export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.result_delete_confirm)
            .setPositiveButton(R.string.result_delete) { _, _ ->
                ResultStore.delete(this, fileName)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_FILE = "file"
    }
}
