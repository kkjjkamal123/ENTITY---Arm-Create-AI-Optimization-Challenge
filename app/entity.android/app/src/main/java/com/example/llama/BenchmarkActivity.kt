package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

/**
 * Runs the same synthetic PP/TG benchmark N times per configuration — naïve
 * (all cores, explicit thread count) vs ENTITY's shipped auto configuration
 * (generation pinned to the performance cores, prompt across all cores) — with a
 * thermal cooldown before every pass, and measures power draw so the result
 * shows speed AND energy efficiency, the axis other on-device apps skip.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var engine: InferenceEngine
    private lateinit var prefs: SharedPreferences
    private val batteryManager by lazy { getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    private lateinit var modelTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var headlineTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var runBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var runsGroup: RadioGroup
    private lateinit var runningBox: View
    private lateinit var resultsBox: View
    private lateinit var table: LinearLayout
    private lateinit var progress: ProgressBar

    private var lastResultText: String? = null
    private var lastResult: Result? = null

    private data class Pass(
        val pp: Double,
        val tg: Double,
        val watts: Double,
        val tokPerW: Double,
        val ttftMs: Double,
        val startTempC: Double,
    )

    private data class Config(val label: String, val key: String, val threads: Int, val runs: List<Pass>)
    private data class Result(val naive: Config, val opt: Config, val charging: Boolean)
    private data class Stat(val median: Double, val sd: Double, val n: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        modelTv = findViewById(R.id.bench_model)
        statusTv = findViewById(R.id.bench_status)
        headlineTv = findViewById(R.id.bench_headline)
        noteTv = findViewById(R.id.bench_note)
        runBtn = findViewById(R.id.run_bench)
        copyBtn = findViewById(R.id.bench_copy)
        exportBtn = findViewById(R.id.bench_export)
        runsGroup = findViewById(R.id.bench_runs)
        runningBox = findViewById(R.id.bench_running)
        resultsBox = findViewById(R.id.bench_results)
        table = findViewById(R.id.bench_table)
        progress = findViewById(R.id.bench_progress)

        engine = AiChat.getInferenceEngine(applicationContext)
        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        modelTv.text = intent.getStringExtra(EXTRA_MODEL) ?: "Loaded model"

        runBtn.setOnClickListener { runBenchmark() }
        copyBtn.setOnClickListener { copyResult() }
        exportBtn.setOnClickListener { exportCsv() }
        exportBtn.isEnabled = false
    }

    private fun selectedRuns() = when (runsGroup.checkedRadioButtonId) {
        R.id.bench_runs_1 -> 1
        R.id.bench_runs_5 -> 5
        else -> 3
    }

    private fun setRunsEnabled(enabled: Boolean) {
        for (i in 0 until runsGroup.childCount) runsGroup.getChildAt(i).isEnabled = enabled
    }

    private fun runBenchmark() {
        val nRuns = selectedRuns()
        runBtn.isEnabled = false
        setRunsEnabled(false)
        resultsBox.visibility = View.GONE
        runningBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { doBenchmark(nRuns) } }
            runningBox.visibility = View.GONE
            runBtn.isEnabled = true
            setRunsEnabled(true)
            outcome
                .onSuccess { showResults(it) }
                .onFailure {
                    if (it !is CancellationException) {
                        Toast.makeText(this@BenchmarkActivity, "Benchmark failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private suspend fun doBenchmark(nRuns: Int): Result {
        val v = Settings.load(prefs)
        val ctx = prefs.getInt(Settings.KEY_ACTIVE_CTX, if (v.ctx > 0) v.ctx else Settings.DEF_CTX)
        val restoreThreads = if (v.auto) 0 else v.threads
        val charging = isCharging()
        val baselineC = readTempC()
        val coolTargetC = if (baselineC > 0.0) maxOf(baselineC + COOL_MARGIN_C, MIN_COOL_TARGET_C) else 0.0
        try {
            status("Warming up…")
            engine.applyConfig(ctx, OPT_THREADS_AUTO, v.temp, v.topK, v.topP)
            engine.bench(64, 16, PL, 1)   // discarded — pages in weights, warms caches

            val naive = runConfig("Naïve", "naive", NAIVE_THREADS, nRuns, ctx, v, coolTargetC)
            val opt = runConfig("Optimized", "optimized", OPT_THREADS_AUTO, nRuns, ctx, v, coolTargetC)

            if (stat(naive.runs.map { it.tg }).n == 0 || stat(opt.runs.map { it.tg }).n == 0) {
                error("Engine returned no timing — try again.")
            }
            return Result(naive, opt, charging)
        } finally {
            withContext(NonCancellable) { engine.applyConfig(ctx, restoreThreads, v.temp, v.topK, v.topP) }
        }
    }

    private suspend fun runConfig(
        label: String,
        key: String,
        threads: Int,
        nRuns: Int,
        ctx: Int,
        v: Settings.Values,
        coolTargetC: Double,
    ): Config {
        engine.applyConfig(ctx, threads, v.temp, v.topK, v.topP)   // 0 = auto, exactly what the app ships
        val genThreads = if (threads <= 0) autoGenThreads() else threads
        val runs = ArrayList<Pass>(nRuns)
        for (i in 1..nRuns) {
            val prefix = "$label ($genThreads threads) — run $i/$nRuns"
            cooldown(prefix, coolTargetC)
            val startTempC = readTempC()
            Log.i(
                TAG,
                String.format(
                    Locale.US, "%s run %d/%d start: threads=%d battery=%.1fC thermalStatus=%d",
                    key, i, nRuns, genThreads, startTempC, powerManager.currentThermalStatus
                )
            )
            status("$prefix…")
            runs.add(runPass(startTempC))
        }
        return Config(label, key, genThreads, runs)
    }

    // Generation threads the native side derives in auto mode — mirrors init_context()
    // in ai_chat.cpp: online cores minus headroom, clamped to the fast-core range.
    private fun autoGenThreads() = (Runtime.getRuntime().availableProcessors() - THREAD_HEADROOM)
        .coerceIn(DeviceOptimizer.MIN_THREADS, DeviceOptimizer.MAX_THREADS)

    private suspend fun cooldown(prefix: String, targetC: Double) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            val t = readTempC()
            val cooled = t <= 0.0 || targetC <= 0.0 || t <= targetC
            if (elapsed >= MIN_PAUSE_MS && (cooled || elapsed >= MAX_COOLDOWN_MS)) break
            status(
                if (!cooled) {
                    "$prefix — cooling down… ${"%.1f".format(t)}°C, target ${"%.1f".format(targetC)}°C"
                } else {
                    "$prefix — cooling down… ${((MIN_PAUSE_MS - elapsed) / 1000 + 1).coerceAtLeast(1)}s"
                }
            )
            delay(1_000)
        }
    }

    private suspend fun runPass(startTempC: Double): Pass = coroutineScope {
        val samples = ArrayList<Double>()
        val voltage = readVoltageMv()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val ua = readCurrentUa()
                if (ua != null) samples.add(PowerMath.watts(ua, voltage))
                delay(150)
            }
        }
        val md = engine.bench(PP, TG, PL, NR)
        sampler.cancelAndJoin()   // fully stop the sampler before reading `samples`
        val watts = samples.filter { it > 0.0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val pp = parseSpeed(md, "pp")
        val tg = parseSpeed(md, "tg")
        val ttftMs = if (pp > 0.0 && tg > 0.0) PP * 1000.0 / pp + PL * 1000.0 / tg else 0.0
        Pass(pp, tg, watts, if (watts > 0.0) tg / watts else 0.0, ttftMs, startTempC)
    }

    private suspend fun status(text: String) = withContext(Dispatchers.Main) { statusTv.text = text }

    // Pull the t/s number out of benchModel's markdown table row (| … | pp 512 | 18.4 ± 0 |).
    private fun parseSpeed(md: String, tag: String): Double {
        md.lineSequence().forEach { line ->
            if (line.contains("| $tag ")) {
                val last = line.split("|").map { it.trim() }.lastOrNull { it.isNotEmpty() }
                val num = last?.substringBefore("±")?.trim()?.toDoubleOrNull()
                if (num != null) return num
            }
        }
        return 0.0
    }

    private fun stat(xs: List<Double>): Stat {
        val v = xs.filter { it > 0.0 }
        if (v.isEmpty()) return Stat(0.0, 0.0, 0)
        val s = v.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        return Stat(median, sd, v.size)
    }

    private fun readCurrentUa(): Long? {
        val v = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (v == Long.MIN_VALUE || v == 0L) null else v
    }

    private fun readVoltageMv(): Int {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    }

    private fun readTempC(): Double {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths < 0) 0.0 else tenths / 10.0
    }

    private fun isCharging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun showResults(r: Result) {
        lastResult = r
        exportBtn.isEnabled = true
        resultsBox.visibility = View.VISIBLE

        val n = r.naive.runs.size
        val nPp = stat(r.naive.runs.map { it.pp })
        val oPp = stat(r.opt.runs.map { it.pp })
        val nTg = stat(r.naive.runs.map { it.tg })
        val oTg = stat(r.opt.runs.map { it.tg })
        val nW = stat(r.naive.runs.map { it.watts })
        val oW = stat(r.opt.runs.map { it.watts })
        val nEff = stat(r.naive.runs.map { it.tokPerW })
        val oEff = stat(r.opt.runs.map { it.tokPerW })
        val nTtft = stat(r.naive.runs.map { it.ttftMs })
        val oTtft = stat(r.opt.runs.map { it.ttftMs })
        val nTemp = stat(r.naive.runs.map { it.startTempC })
        val oTemp = stat(r.opt.runs.map { it.startTempC })
        val powerValid = !r.charging && nW.n > 0 && oW.n > 0 && nEff.n > 0 && oEff.n > 0

        val spd = if (nTg.median > 0) (oTg.median / nTg.median - 1) * 100 else 0.0
        val headline = StringBuilder("Big-core optimization: decode ${signed(spd)} faster")
        if (powerValid) headline.append(" · ${"%.1f".format(oEff.median / nEff.median)}× more efficient")
        headlineTv.text = headline.toString()

        table.removeAllViews()
        addRow("", "Naïve\n$NAIVE_THREADS cores", "Optimized\n${r.opt.threads}× perf cores", "Δ", header = true)
        addRow("Prompt  t/s", cellStat(nPp), cellStat(oPp), pct(nPp.median, oPp.median))
        addRow("Decode  t/s", cellStat(nTg), cellStat(oTg), pct(nTg.median, oTg.median))
        addRow("TTFT*  ms", cellStat(nTtft), cellStat(oTtft), pct(nTtft.median, oTtft.median))
        if (powerValid) {
            addRow("Power  W", cellStat(nW), cellStat(oW), "")
            addRow("Efficiency  tok/W", cellStat(nEff), cellStat(oEff), pct(nEff.median, oEff.median))
        } else {
            addRow("Power  W", "—", "—", "")
        }
        addRow("Start temp  °C", if (nTemp.n > 0) fmt(nTemp.median) else "—", if (oTemp.n > 0) fmt(oTemp.median) else "—", "")

        val note = StringBuilder(
            "Synthetic llama-bench test (PP $PP / TG $TG), $n run${if (n > 1) "s" else ""} per config — " +
            "median${if (n > 1) " ±σ (population)" else ""} across runs. Cooldown before every pass: ≥${MIN_PAUSE_MS / 1000}s pause, " +
            "then up to ${MAX_COOLDOWN_MS / 1000}s until the battery returns to within ${"%.1f".format(COOL_MARGIN_C)}°C of its pre-benchmark temperature (never waiting below ${"%.1f".format(MIN_COOL_TARGET_C)}°C). " +
            "*TTFT is derived from each run's measured rates ($PP-token prompt eval + one decode step), not a live chat measurement. " +
            "Numbers are comparable across apps, and higher than live chat speed because the KV cache is minimal. " +
            "Naïve = $NAIVE_THREADS threads spread across all cores; Optimized = ENTITY's shipped auto configuration, " +
            "which pins generation to the ${r.opt.threads} performance cores while letting prompt processing use all cores."
        )
        if (!powerValid) note.append("\n\n⚠ Phone is charging — power/efficiency need it UNPLUGGED to be valid, so they're hidden. Speed numbers above are still valid.")
        noteTv.text = note.toString()

        lastResultText = buildString {
            appendLine("ENTITY benchmark — ${modelTv.text}")
            appendLine(headlineTv.text)
            appendLine("Runs/config : $n (median${if (n > 1) " ±σ" else ""})")
            appendLine("Prompt  t/s : naive ${statText(nPp)}  opt ${statText(oPp)}")
            appendLine("Decode  t/s : naive ${statText(nTg)}  opt ${statText(oTg)}")
            appendLine("TTFT*   ms  : naive ${statText(nTtft)}  opt ${statText(oTtft)}")
            if (powerValid) {
                appendLine("Power   W   : naive ${statText(nW)}  opt ${statText(oW)}")
                appendLine("tok/W       : naive ${statText(nEff)}  opt ${statText(oEff)}")
            }
            appendLine("Start   °C  : naive ${statText(nTemp)}  opt ${statText(oTemp)}")
            appendLine("*derived: PP$PP prompt eval + one decode step")
        }.trim()
    }

    private fun cellStat(s: Stat) = when {
        s.n <= 0 -> "—"
        s.n == 1 -> fmt(s.median)
        else -> "${fmt(s.median)}\n±${fmtSd(s.sd)}"
    }

    private fun statText(s: Stat) = when {
        s.n <= 0 -> "—"
        s.n == 1 -> fmt(s.median)
        else -> "${fmt(s.median)} ±${fmtSd(s.sd)}"
    }

    private fun addRow(metric: String, naive: String, opt: String, delta: String, header: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val onSurface = getColor(R.color.on_surface)
        val muted = getColor(R.color.muted)
        val accent = getColor(R.color.accent)
        fun cell(text: String, weight: Float, color: Int, bold: Boolean, gravity: Int) {
            row.addView(TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = 13.5f
                this.gravity = gravity
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            })
        }
        cell(metric, 2.2f, if (header) onSurface else muted, header, Gravity.START)
        cell(naive, 1.6f, onSurface, header, Gravity.END)
        cell(opt, 1.7f, if (header) onSurface else accent, true, Gravity.END)
        cell(delta, 1.1f, accent, true, Gravity.END)
        table.addView(row)
    }

    private fun copyResult() {
        val text = lastResultText ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ENTITY benchmark", text))
        Toast.makeText(this, "Result copied", Toast.LENGTH_SHORT).show()
    }

    private fun exportCsv() {
        if (lastResult == null) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "entity_bench_${System.currentTimeMillis()}.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EXPORT_CSV)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_CSV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val csv = buildCsv(lastResult ?: return)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(this@BenchmarkActivity, if (ok) "CSV exported" else "CSV export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCsv(r: Result): String = buildString {
        fun esc(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
        fun row(config: String, runIndex: String, metric: String, value: String, unit: String) =
            appendLine(listOf(config, runIndex, metric, value, unit).joinToString(",") { esc(it) })
        fun num(x: Double) = String.format(Locale.US, "%.3f", x)

        appendLine("config,run_index,metric,value,unit")
        row("meta", "", "app", "ENTITY", "")
        row("meta", "", "model", modelTv.text.toString(), "")
        row("meta", "", "device", "${Build.MANUFACTURER} ${Build.MODEL}", "")
        row("meta", "", "android", Build.VERSION.RELEASE ?: "", "")
        row("meta", "", "charging", r.charging.toString(), "")
        row("meta", "", "pp", PP.toString(), "tokens")
        row("meta", "", "tg", TG.toString(), "tokens")
        row("meta", "", "runs_per_config", r.naive.runs.size.toString(), "")
        row("meta", "", "threads_naive", r.naive.threads.toString(), "")
        row("meta", "", "threads_optimized", r.opt.threads.toString(), "")

        for (c in listOf(r.naive, r.opt)) {
            c.runs.forEachIndexed { i, p ->
                val idx = (i + 1).toString()
                row(c.key, idx, "pp", num(p.pp), "tok/s")
                row(c.key, idx, "tg", num(p.tg), "tok/s")
                row(c.key, idx, "power", num(p.watts), "W")
                row(c.key, idx, "tok_per_w", num(p.tokPerW), "tok/W")
                row(c.key, idx, "ttft_pp${PP}_derived", num(p.ttftMs), "ms")
                row(c.key, idx, "start_temp", num(p.startTempC), "C")
            }
            fun agg(metric: String, unit: String, sel: (Pass) -> Double) {
                val st = stat(c.runs.map(sel))
                row(c.key, "median", metric, num(st.median), unit)
                row(c.key, "stddev", metric, num(st.sd), unit)
            }
            agg("pp", "tok/s") { it.pp }
            agg("tg", "tok/s") { it.tg }
            agg("power", "W") { it.watts }
            agg("tok_per_w", "tok/W") { it.tokPerW }
            agg("ttft_pp${PP}_derived", "ms") { it.ttftMs }
            agg("start_temp", "C") { it.startTempC }
        }
    }

    private fun fmt(x: Double) = if (x >= 100) "%.0f".format(x) else "%.1f".format(x)

    private fun fmtSd(x: Double) = when {
        x >= 10 -> "%.0f".format(x)
        x >= 1 -> "%.1f".format(x)
        else -> "%.2f".format(x)
    }

    private fun signed(p: Double) = (if (p >= 0) "+" else "") + "%.0f%%".format(p)
    private fun pct(from: Double, to: Double) = if (from <= 0) "—" else signed((to / from - 1) * 100)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODEL = "model"
        private const val TAG = "EntityBench"
        private const val PP = 512
        private const val TG = 128
        private const val PL = 1
        private const val NR = 1
        // 0 = auto: the engine picks the generation threads, pins them to the fastest
        // cores and widens prompt processing to all cores — the shipped configuration.
        private const val OPT_THREADS_AUTO = 0
        private const val NAIVE_THREADS = 8
        // ai_chat.cpp N_THREADS_HEADROOM
        private const val THREAD_HEADROOM = 2
        private const val MIN_PAUSE_MS = 15_000L
        private const val MAX_COOLDOWN_MS = 90_000L
        private const val COOL_MARGIN_C = 0.5
        // Cooldown never waits below this — ambient in hot climates keeps batteries above ~37°C.
        private const val MIN_COOL_TARGET_C = 37.5
        private const val REQ_EXPORT_CSV = 41
    }
}
