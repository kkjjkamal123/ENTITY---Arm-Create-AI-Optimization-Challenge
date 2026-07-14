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
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Runs the same synthetic PP/TG benchmark N times for each of three configurations,
 * with a thermal cooldown before every pass, measuring power draw so the result
 * shows speed AND energy efficiency, the axis other on-device apps skip.
 *
 * The three arms are an ablation: naïve and Auto differ in both thread count and
 * core placement, so a two-arm result cannot say which one earns the speed-up.
 * The middle arm holds the thread count at Auto's value and drops only the
 * affinity, so the decode gap between it and Auto is the value of pinning alone.
 *
 *   naïve        8 threads, every core, default scheduler
 *   threads-only Auto's thread count, no affinity, no pinned pool (an upstream
 *                llama.cpp `-t N` run: the honest baseline a tuning-aware user hits)
 *   Auto         ENTITY's shipped path: both phases on the fast-core thread count,
 *                pinned to the performance cluster
 *
 * Both decode and prompt are clean rows: every arm runs both phases on the same
 * thread count, so naïve -> threads-only isolates the thread count and
 * threads-only -> Auto isolates the core placement.
 *
 * Measured on a Dimensity 7300, and the reason these arms are not ceremony: they
 * disproved this project's own flagship optimization. Across six runs on two models
 * the thread count earns +81% to +94% of decode, and the pinning earns ~0%. One 3B
 * run showed +12%, but two others showed 0% and -16%; single 3B runs swing about
 * +/-15%, so that was noise, not a model-size effect.
 *
 * The affinity code still ships - it is free, and another SoC may answer differently.
 * It is simply no longer credited with the speed-up. This screen is what tells the
 * user which decisions actually pay on their phone.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var engine: InferenceEngine
    private lateinit var prefs: SharedPreferences
    private val batteryManager by lazy { getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    // Core layout, read once: which CPUs are the performance cluster and which are the
    // little cores. Same max-clock ranking the native side pins by, so the frequency
    // trace splits the way the optimization does.
    private val maxFreqsKhz by lazy { DeviceOptimizer.maxFreqsKhz() }
    private val fastCores by lazy { DeviceOptimizer.fastCoreIndices(maxFreqsKhz) }
    private val littleCores by lazy { maxFreqsKhz.indices.filter { it !in fastCores.toSet() } }

    private lateinit var modelTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var headlineTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var runBtn: Button
    private lateinit var sustainedBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var runsGroup: RadioGroup
    private lateinit var runningBox: View
    private lateinit var resultsBox: View
    private lateinit var table: LinearLayout
    private lateinit var progress: ProgressBar

    private var lastResultText: String? = null
    private var pendingCsvBuilder: (() -> String)? = null
    private var pendingCsvPath: String? = null

    private data class Pass(
        val pp: Double,
        val tg: Double,
        val watts: Double,
        val tokPerW: Double,
        val ttftMs: Double,
        val startTempC: Double,
        val telemetry: List<TelemetrySample>,
    ) {
        private val usableTelemetry get() = telemetry.drop(1)
        val averageProcessCpuPercent get() = usableTelemetry.let { samples ->
            if (samples.isEmpty()) 0.0 else samples.map { it.processCpuPercent }.average()
        }
        val minimumFreeGb get() = telemetry.map { it.freeGb }.filter { it > 0.0 }.minOrNull() ?: 0.0
        val peakBatteryTempC get() = telemetry.maxOfOrNull { it.batteryTempC } ?: startTempC
        val peakThermalStatus get() = telemetry.maxOfOrNull { it.thermalStatus } ?: 0

        // Mean live clock of the performance cores across the pass. A pinned decode should
        // hold these near their ceiling; an unpinned one lets work drift onto the little
        // cores and the mean sags. 0 when the kernel hides scaling_cur_freq.
        private fun meanFreq(indices: List<Int>) = usableTelemetry
            .flatMap { s -> indices.mapNotNull { s.cpuFreqMhz.getOrNull(it) } }
            .filter { it > 0 }
            .let { if (it.isEmpty()) 0.0 else it.average() }

        fun meanFastCoreFreqMhz(fast: List<Int>) = meanFreq(fast)
        fun meanLittleCoreFreqMhz(little: List<Int>) = meanFreq(little)
    }

    // App-process CPU can exceed 100% when llama.cpp uses more than one CPU core.
    // cpuFreqMhz is the live clock of every core at this instant, index = cpu number.
    private data class TelemetrySample(
        val elapsedMs: Long,
        val watts: Double,
        val freeGb: Double,
        val batteryTempC: Double,
        val thermalStatus: Int,
        val processCpuPercent: Double,
        val cpuFreqMhz: List<Int>,
    )

    private data class Config(
        val label: String,
        val key: String,
        val threads: Int,
        val pinned: Boolean,
        val runs: List<Pass>,
    )
    private data class Result(
        val naive: Config,
        val threadsOnly: Config,
        val opt: Config,
        val charging: Boolean,
        val benchmarkStartTempC: Double,
        val benchmarkStartThermalStatus: Int,
    ) {
        val configs get() = listOf(naive, threadsOnly, opt)
    }
    // No cooldown between passes within a block: heat is meant to accumulate. Only
    // threads-only vs Auto — naïve isn't part of the pinning question this isolates.
    private data class SustainedResult(val threadsOnly: Config, val opt: Config)
    private data class Stat(val median: Double, val sd: Double, val n: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)

        // Recover the staged export if the system killed us while the file picker was up.
        pendingCsvPath = savedInstanceState?.getString(STATE_PENDING_CSV)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        modelTv = findViewById(R.id.bench_model)
        statusTv = findViewById(R.id.bench_status)
        headlineTv = findViewById(R.id.bench_headline)
        noteTv = findViewById(R.id.bench_note)
        runBtn = findViewById(R.id.run_bench)
        sustainedBtn = findViewById(R.id.run_sustained_bench)
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
        sustainedBtn.setOnClickListener { runSustainedBenchmark() }
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
        sustainedBtn.isEnabled = false
        setRunsEnabled(false)
        resultsBox.visibility = View.GONE
        runningBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { doBenchmark(nRuns) } }
            runningBox.visibility = View.GONE
            runBtn.isEnabled = true
            sustainedBtn.isEnabled = true
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

    // Isolates whether affinity pinning pays off under sustained heat, which the
    // controlled benchmark above cannot see: it cools back to baseline before every
    // single pass by design. Here only threads-only and Auto run (naive is not part of
    // the pinning question), each as SUSTAINED_PASSES back-to-back passes with just a
    // fixed gap - no wait for the battery to cool - so heat accumulates within a block.
    // Both blocks start from the same cooled baseline, so pass 1 is comparable across
    // arms even though later passes are not blind to how long the phone has been busy.
    private fun runSustainedBenchmark() {
        runBtn.isEnabled = false
        sustainedBtn.isEnabled = false
        setRunsEnabled(false)
        resultsBox.visibility = View.GONE
        runningBox.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { doSustainedBenchmark() } }
            runningBox.visibility = View.GONE
            runBtn.isEnabled = true
            sustainedBtn.isEnabled = true
            setRunsEnabled(true)
            outcome
                .onSuccess { showSustainedResults(it) }
                .onFailure {
                    if (it !is CancellationException) {
                        Toast.makeText(this@BenchmarkActivity, "Sustained test failed: ${it.message}", Toast.LENGTH_LONG).show()
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
        val benchmarkStartThermalStatus = powerManager.currentThermalStatus
        val coolTargetC = if (baselineC > 0.0) maxOf(baselineC + COOL_MARGIN_C, MIN_COOL_TARGET_C) else 0.0
        try {
            status("Warming up…")
            engine.applyConfig(ctx, OPT_THREADS_AUTO, v.temp, v.topK, v.topP)
            engine.bench(64, 16, PL, 1)   // discarded — pages in weights, warms caches

            // Ablation order: naïve → threads-only → Auto. Every arm gets the same
            // cooldown, so the order does not favour the last one.
            val naive = runConfig("Naïve", "naive", NAIVE_THREADS, true, nRuns, ctx, v, coolTargetC)
            val threadsOnly =
                runConfig("Threads only", "threads_only", autoGenThreads(), false, nRuns, ctx, v, coolTargetC)
            val opt = runConfig("Optimized", "optimized", OPT_THREADS_AUTO, true, nRuns, ctx, v, coolTargetC)

            if (listOf(naive, threadsOnly, opt).any { stat(it.runs.map { p -> p.tg }).n == 0 }) {
                error("Engine returned no timing — try again.")
            }
            return Result(naive, threadsOnly, opt, charging, baselineC, benchmarkStartThermalStatus)
        } finally {
            // pinCores back to the shipped default: chat decode must re-pin after the
            // threads-only arm turned affinity off.
            withContext(NonCancellable) {
                engine.applyConfig(ctx, restoreThreads, v.temp, v.topK, v.topP, pinCores = true)
            }
        }
    }

    private suspend fun runConfig(
        label: String,
        key: String,
        threads: Int,
        pinCores: Boolean,
        nRuns: Int,
        ctx: Int,
        v: Settings.Values,
        coolTargetC: Double,
    ): Config {
        // 0 threads = auto, exactly what the app ships. pinCores = false is the
        // ablation arm: same thread count, scheduler-placed, no pinned pool.
        engine.applyConfig(ctx, threads, v.temp, v.topK, v.topP, pinCores)
        val genThreads = if (threads <= 0) autoGenThreads() else threads
        val runs = ArrayList<Pass>(nRuns)
        for (i in 1..nRuns) {
            val placement = if (pinCores) "pinned" else "no pin"
            val prefix = "$label ($genThreads threads, $placement) — run $i/$nRuns"
            cooldown(prefix, coolTargetC)
            val startTempC = readTempC()
            Log.i(
                TAG,
                String.format(
                    Locale.US, "%s run %d/%d start: threads=%d pinned=%b battery=%.1fC thermalStatus=%d",
                    key, i, nRuns, genThreads, pinCores, startTempC, powerManager.currentThermalStatus
                )
            )
            status("$prefix…")
            runs.add(runPass(startTempC))
        }
        return Config(label, key, genThreads, pinCores, runs)
    }

    private suspend fun doSustainedBenchmark(): SustainedResult {
        val v = Settings.load(prefs)
        val ctx = prefs.getInt(Settings.KEY_ACTIVE_CTX, if (v.ctx > 0) v.ctx else Settings.DEF_CTX)
        val restoreThreads = if (v.auto) 0 else v.threads
        val baselineC = readTempC()
        val coolTargetC = if (baselineC > 0.0) maxOf(baselineC + COOL_MARGIN_C, MIN_COOL_TARGET_C) else 0.0
        try {
            status("Warming up…")
            engine.applyConfig(ctx, OPT_THREADS_AUTO, v.temp, v.topK, v.topP)
            engine.bench(64, 16, PL, 1)   // discarded — pages in weights, warms caches

            val threadsOnly =
                runSustainedConfig("Threads only", "threads_only", autoGenThreads(), false, ctx, v, coolTargetC)
            val opt = runSustainedConfig("Optimized", "optimized", OPT_THREADS_AUTO, true, ctx, v, coolTargetC)
            if (threadsOnly.runs.isEmpty() || opt.runs.isEmpty()) {
                error("Engine returned no timing — try again.")
            }
            return SustainedResult(threadsOnly, opt)
        } finally {
            withContext(NonCancellable) {
                engine.applyConfig(ctx, restoreThreads, v.temp, v.topK, v.topP, pinCores = true)
            }
        }
    }

    private suspend fun runSustainedConfig(
        label: String,
        key: String,
        threads: Int,
        pinCores: Boolean,
        ctx: Int,
        v: Settings.Values,
        coolTargetC: Double,
    ): Config {
        engine.applyConfig(ctx, threads, v.temp, v.topK, v.topP, pinCores)
        val genThreads = if (threads <= 0) autoGenThreads() else threads
        // Same cooled starting point for both blocks; no cooldown between the passes
        // that follow, so this pass loop is where heat is allowed to build.
        cooldown("$label — cooling to baseline before sustained run", coolTargetC)
        val runs = ArrayList<Pass>(SUSTAINED_PASSES)
        for (i in 1..SUSTAINED_PASSES) {
            val placement = if (pinCores) "pinned" else "no pin"
            status("$label ($genThreads threads, $placement) — sustained pass $i/$SUSTAINED_PASSES…")
            val startTempC = readTempC()
            Log.i(
                TAG,
                String.format(
                    Locale.US, "sustained %s pass %d/%d start: threads=%d pinned=%b battery=%.1fC thermalStatus=%d",
                    key, i, SUSTAINED_PASSES, genThreads, pinCores, startTempC, powerManager.currentThermalStatus
                )
            )
            runs.add(runPass(startTempC))
            if (i < SUSTAINED_PASSES) delay(SUSTAINED_GAP_MS)
        }
        return Config(label, key, genThreads, pinCores, runs)
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
        val telemetry = ArrayList<TelemetrySample>()
        val voltage = readVoltageMv()
        val benchmarkStartMs = SystemClock.elapsedRealtime()
        var lastCpuWallMs = benchmarkStartMs
        var lastProcessCpuMs = android.os.Process.getElapsedCpuTime()
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val ua = readCurrentUa()
                val watts = ua?.let { PowerMath.watts(it, voltage) } ?: 0.0
                if (watts > 0.0) samples.add(watts)
                val processCpuMs = android.os.Process.getElapsedCpuTime()
                val elapsedMs = now - lastCpuWallMs
                val processCpuPercent = if (elapsedMs > 0L) {
                    (processCpuMs - lastProcessCpuMs).coerceAtLeast(0L) * 100.0 / elapsedMs
                } else {
                    0.0
                }
                telemetry.add(
                    TelemetrySample(
                        elapsedMs = now - benchmarkStartMs,
                        watts = watts,
                        freeGb = availableGb(),
                        batteryTempC = readTempC(),
                        thermalStatus = powerManager.currentThermalStatus,
                        processCpuPercent = processCpuPercent,
                        cpuFreqMhz = DeviceOptimizer.currentFreqsKhz().map { (it / 1000).toInt() },
                    )
                )
                lastCpuWallMs = now
                lastProcessCpuMs = processCpuMs
                delay(150)
            }
        }
        val md = engine.bench(PP, TG, PL, NR)
        sampler.cancelAndJoin()   // fully stop the sampler before reading `samples`
        val watts = samples.filter { it > 0.0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        val pp = parseSpeed(md, "pp")
        val tg = parseSpeed(md, "tg")
        val ttftMs = if (pp > 0.0 && tg > 0.0) PP * 1000.0 / pp + PL * 1000.0 / tg else 0.0
        Pass(pp, tg, watts, if (watts > 0.0) tg / watts else 0.0, ttftMs, startTempC, telemetry)
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

    private fun availableGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024.0 * 1024.0 * 1024.0)
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
        pendingCsvBuilder = { buildCsv(r) }
        exportBtn.isEnabled = true
        resultsBox.visibility = View.VISIBLE

        val n = r.naive.runs.size
        // Per-metric stats for all three arms, in table order.
        fun stats(sel: (Pass) -> Double) = r.configs.map { stat(it.runs.map(sel)) }
        val pp = stats { it.pp }
        val tg = stats { it.tg }
        val watts = stats { it.watts }
        val eff = stats { it.tokPerW }
        val ttft = stats { it.ttftMs }
        val temp = stats { it.startTempC }
        val cpu = stats { it.averageProcessCpuPercent }
        val free = stats { it.minimumFreeGb }
        val peakTemp = stats { it.peakBatteryTempC }
        val fastFreq = stats { it.meanFastCoreFreqMhz(fastCores) }
        val littleFreq = stats { it.meanLittleCoreFreqMhz(littleCores) }
        val powerValid = !r.charging && watts.all { it.n > 0 } && eff.all { it.n > 0 }

        val (naiveTg, threadsTg, optTg) = Triple(tg[0].median, tg[1].median, tg[2].median)
        val spd = if (naiveTg > 0) (optTg / naiveTg - 1) * 100 else 0.0
        val headline = StringBuilder("Big-core optimization: decode ${signed(spd)} faster")
        if (powerValid) headline.append(" · ${"%.1f".format(eff[2].median / eff[0].median)}× more efficient")
        headlineTv.text = headline.toString()

        table.removeAllViews()
        addRow(
            "",
            "Naïve\n$NAIVE_THREADS threads",
            "Threads only\n${r.threadsOnly.threads}× no pin",
            "ENTITY Auto\n${r.opt.threads}× perf pinned",
            "Δ",
            header = true,
        )
        addRow("Prompt†  t/s", *cells(pp), pct(pp[0].median, pp[2].median))
        addRow("Decode  t/s", *cells(tg), pct(tg[0].median, tg[2].median))
        addRow("TTFT*  ms", *cells(ttft), pct(ttft[0].median, ttft[2].median))
        if (powerValid) {
            addRow("Power  W", *cells(watts), "")
            addRow("Efficiency  tok/W", *cells(eff), pct(eff[0].median, eff[2].median))
        } else {
            addRow("Power  W", "—", "—", "—", "")
        }
        addRow("App CPU  %", *cells(cpu), "")
        // Absent when the kernel hides scaling_cur_freq: show nothing rather than a row of dashes.
        if (fastFreq.any { it.n > 0 }) addRow("Perf-core clock  MHz", *cells(fastFreq), "")
        if (littleFreq.any { it.n > 0 }) addRow("Little-core clock  MHz", *cells(littleFreq), "")
        addRow("Free RAM min  GB", *cells(free), "")
        addRow("Start temp  °C", *cells(temp), "")
        addRow("Peak battery  °C", *cells(peakTemp), "")
        addRow("Peak thermal", *r.configs.map { thermalSummary(it) }.toTypedArray(), "")

        // The whole point of the middle arm: split the naïve → Auto decode gain into
        // the part any `-t N` user already gets and the part core pinning adds on top.
        val attribution = if (naiveTg > 0 && threadsTg > 0) {
            "\n\nAttribution — decode: dropping to ${r.threadsOnly.threads} threads alone is " +
                "${signed((threadsTg / naiveTg - 1) * 100)} over naïve; pinning those threads to the " +
                "performance cores adds ${signed((optTg / threadsTg - 1) * 100)} on top. " +
                "The middle arm is what an upstream llama.cpp `-t ${r.threadsOnly.threads}` run does: " +
                "same thread count, no affinity, no pinned pool."
        } else {
            ""
        }

        val note = StringBuilder(
            "Synthetic llama-bench test (PP $PP / TG $TG), $n run${if (n > 1) "s" else ""} per config — " +
            "median${if (n > 1) " ±σ (population)" else ""} across runs. Cooldown before every pass: ≥${MIN_PAUSE_MS / 1000}s pause, " +
            "then up to ${MAX_COOLDOWN_MS / 1000}s until the battery returns to within ${"%.1f".format(COOL_MARGIN_C)}°C of its pre-benchmark temperature (never waiting below ${"%.1f".format(MIN_COOL_TARGET_C)}°C). " +
            "*TTFT is derived from each run's measured rates ($PP-token prompt eval + one decode step), not a live chat measurement. " +
            "Numbers are comparable across apps, and higher than live chat speed because the KV cache is minimal. " +
            "Naïve = $NAIVE_THREADS threads spread across all cores; Threads only = the same thread count as Auto with affinity off; " +
            "ENTITY Auto = the shipped configuration, which runs both phases on ${r.opt.threads} threads pinned to the " +
            "performance cores. Δ compares Auto with naïve." +
            "\n\n†Both arms now run prompt processing on the same thread count, so this row is a clean ablation too: " +
            "naïve → threads-only is the thread count, threads-only → Auto is the core placement."
        )
        note.append(attribution)
        val peakThermal = r.configs.maxOf { c -> c.runs.maxOfOrNull { it.peakThermalStatus } ?: 0 }
        if (peakThermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            note.append("\n\nThermal analysis: Android reached ${thermalLabel(peakThermal)}. ENTITY's streaming Auto guard cooperatively backs off at MODERATE or higher; this raw synthetic benchmark records the condition but does not apply chat delays.")
        }
        if (!powerValid) note.append("\n\n⚠ Phone is charging — power/efficiency need it UNPLUGGED to be valid, so they're hidden. Speed numbers above are still valid.")
        noteTv.text = note.toString()

        fun line(name: String, s: List<Stat>) =
            "$name: naive ${statText(s[0])}  threads-only ${statText(s[1])}  auto ${statText(s[2])}"
        lastResultText = buildString {
            appendLine("ENTITY benchmark — ${modelTv.text}")
            appendLine(headlineTv.text)
            appendLine("Runs/config : $n (median${if (n > 1) " ±σ" else ""})")
            appendLine("Arms: naive=$NAIVE_THREADS threads all cores · threads-only=${r.threadsOnly.threads} threads no pin · auto=${r.opt.threads} perf cores pinned")
            appendLine(line("Prompt† t/s", pp))
            appendLine(line("Decode  t/s", tg))
            appendLine(line("TTFT*   ms ", ttft))
            if (powerValid) {
                appendLine(line("Power   W  ", watts))
                appendLine(line("tok/W      ", eff))
            }
            appendLine(line("Start   °C ", temp))
            appendLine(line("App CPU %  ", cpu))
            appendLine(line("Free RAM min GB", free))
            appendLine("Peak thermal: " + r.configs.joinToString("  ") { "${it.key} ${thermalSummary(it)}" })
            if (attribution.isNotEmpty()) appendLine(attribution.trim())
            appendLine("*derived: PP$PP prompt eval + one decode step")
            appendLine("†prompt row is not an isolated ablation: only auto widens PP to all cores")
        }.trim()
    }

    // Threads-only vs Auto, back-to-back, no cooldown inside a block. If pinning only
    // pays off once the little cores have had time to heat up and throttle, this is
    // where it shows: the per-pass decode trend, not the cooled 3-arm median above.
    private fun showSustainedResults(r: SustainedResult) {
        pendingCsvBuilder = { buildSustainedCsv(r) }
        exportBtn.isEnabled = true
        resultsBox.visibility = View.VISIBLE

        fun decodeSeries(c: Config) = c.runs.map { it.tg }
        fun dropPct(series: List<Double>): Double {
            val first = series.firstOrNull { it > 0 } ?: return 0.0
            val last = series.lastOrNull { it > 0 } ?: return 0.0
            return if (first > 0) (last / first - 1) * 100 else 0.0
        }
        val toTg = decodeSeries(r.threadsOnly)
        val optTg = decodeSeries(r.opt)
        val toDrop = dropPct(toTg)
        val optDrop = dropPct(optTg)
        headlineTv.text = "Sustained, no cooldown: threads-only decode ${signed(toDrop)} from pass 1 to " +
            "$SUSTAINED_PASSES · Auto ${signed(optDrop)}"

        table.removeAllViews()
        addRow("Pass", "Threads-only\ntg/s · thermal", "ENTITY Auto\ntg/s · thermal", "", header = true)
        for (i in 0 until SUSTAINED_PASSES) {
            val to = r.threadsOnly.runs.getOrNull(i)
            val op = r.opt.runs.getOrNull(i)
            addRow(
                "#${i + 1}",
                to?.let { "${fmt(it.tg)} · ${thermalLabel(it.peakThermalStatus)}" } ?: "—",
                op?.let { "${fmt(it.tg)} · ${thermalLabel(it.peakThermalStatus)}" } ?: "—",
            )
        }

        noteTv.text = "$SUSTAINED_PASSES back-to-back PP $PP / TG $TG passes per arm, only a fixed " +
            "${SUSTAINED_GAP_MS / 1000}s gap between passes instead of the cooldown-to-baseline the controlled " +
            "benchmark above uses — heat is meant to accumulate within a block. Both arms start their block " +
            "from the same cooled baseline, so pass 1 is comparable across arms, but this is a single session: " +
            "run it more than once, and swap which arm goes first, before trusting the direction of any gap. " +
            "n=1 per pass — read the trend across passes, not any single one."

        lastResultText = buildString {
            appendLine("ENTITY sustained thermal test — ${modelTv.text}")
            appendLine(headlineTv.text)
            appendLine("threads-only tg: " + toTg.joinToString("  ") { fmt(it) })
            appendLine("auto        tg: " + optTg.joinToString("  ") { fmt(it) })
            appendLine("threads-only thermal: " + r.threadsOnly.runs.joinToString("  ") { thermalLabel(it.peakThermalStatus) })
            appendLine("auto        thermal: " + r.opt.runs.joinToString("  ") { thermalLabel(it.peakThermalStatus) })
        }.trim()
    }

    private fun cells(s: List<Stat>) = s.map { cellStat(it) }.toTypedArray()

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

    private fun thermalSummary(c: Config): String = thermalLabel(c.runs.maxOfOrNull { it.peakThermalStatus } ?: 0)

    private fun thermalLabel(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    // cells = naïve, threads-only, Auto, Δ. Auto and Δ are accented: the shipped path
    // and its gain over naïve.
    private fun addRow(metric: String, vararg cells: String, header: Boolean = false) {
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
                textSize = 12f
                this.gravity = gravity
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            })
        }
        cell(metric, 2.0f, if (header) onSurface else muted, header, Gravity.START)
        cell(cells.getOrElse(0) { "" }, 1.5f, onSurface, header, Gravity.END)
        cell(cells.getOrElse(1) { "" }, 1.6f, onSurface, header, Gravity.END)
        cell(cells.getOrElse(2) { "" }, 1.7f, if (header) onSurface else accent, true, Gravity.END)
        cell(cells.getOrElse(3) { "" }, 1.1f, accent, true, Gravity.END)
        table.addView(row)
    }

    private fun copyResult() {
        val text = lastResultText ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ENTITY benchmark", text))
        Toast.makeText(this, "Result copied", Toast.LENGTH_SHORT).show()
    }

    // The CSV is serialised to cache BEFORE the system file picker is launched, and the
    // path is carried through onSaveInstanceState.
    //
    // Why: the picker is a separate process coming to the foreground while this app holds
    // a multi-GB model resident. Android routinely kills this activity behind it. We then
    // come back through onActivityResult on a NEW instance whose `lastResult` is null - but
    // DocumentsUI has already created the destination file. The old code did
    // `buildCsv(lastResult ?: return)`, so it returned early and left a 0-byte CSV behind
    // while still toasting "CSV exported". That is how a benchmark export silently produced
    // an empty file, and why no raw per-pass CSV ever survived to be published.
    private fun exportCsv() {
        val builder = pendingCsvBuilder ?: return
        val cached = runCatching {
            File(cacheDir, "pending_export.csv").apply { writeText(builder()) }
        }.getOrNull()
        if (cached == null) {
            Toast.makeText(this, "Could not prepare CSV", Toast.LENGTH_SHORT).show()
            return
        }
        pendingCsvPath = cached.absolutePath
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "entity_bench_${System.currentTimeMillis()}.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EXPORT_CSV)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCsvPath?.let { outState.putString(STATE_PENDING_CSV, it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_CSV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        // Prefer the file staged before the picker opened; it survives this activity being
        // killed. Only fall back to rebuilding from an in-memory result.
        val staged = pendingCsvPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        val csv = staged?.readText() ?: pendingCsvBuilder?.invoke()
        if (csv.isNullOrEmpty()) {
            // Never leave the caller believing an empty file is a valid export.
            Toast.makeText(this, "Export failed: benchmark result was lost, please re-run", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } != null
                }.getOrDefault(false)
            }
            if (ok) { runCatching { staged?.delete() }; pendingCsvPath = null }
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
        row("meta", "", "app_version", BuildConfig.VERSION_NAME, "")
        row("meta", "", "app_version_code", BuildConfig.VERSION_CODE.toString(), "")
        row("meta", "", "exported_at_epoch_ms", System.currentTimeMillis().toString(), "ms")
        row("meta", "", "model", modelTv.text.toString(), "")
        row("meta", "", "device", "${Build.MANUFACTURER} ${Build.MODEL}", "")
        row("meta", "", "device_manufacturer", Build.MANUFACTURER, "")
        row("meta", "", "device_model", Build.MODEL, "")
        row("meta", "", "device_fingerprint", Build.FINGERPRINT, "")
        row("meta", "", "android", Build.VERSION.RELEASE ?: "", "")
        row("meta", "", "android_sdk", Build.VERSION.SDK_INT.toString(), "")
        row("meta", "", "supported_abis", Build.SUPPORTED_ABIS.joinToString(" "), "")
        row("meta", "", "charging", r.charging.toString(), "")
        row("meta", "", "benchmark_start_temp", num(r.benchmarkStartTempC), "C")
        row("meta", "", "benchmark_start_thermal_status", r.benchmarkStartThermalStatus.toString(), "Android PowerManager status")
        row("meta", "", "pp", PP.toString(), "tokens")
        row("meta", "", "tg", TG.toString(), "tokens")
        row("meta", "", "runs_per_config", r.naive.runs.size.toString(), "")
        row("meta", "", "warmup", "PP 64 / TG 16 / discarded", "")
        row("meta", "", "configuration_order", "naive_then_threads_only_then_optimized", "")
        row("meta", "", "threads_naive", r.naive.threads.toString(), "")
        row("meta", "", "threads_threads_only", r.threadsOnly.threads.toString(), "")
        row("meta", "", "threads_optimized", r.opt.threads.toString(), "")
        for (c in r.configs) {
            row("meta", "", "affinity_${c.key}", if (c.pinned) "pinned_fast_cores" else "none_scheduler_placed", "")
        }
        row("meta", "", "cooldown_minimum", (MIN_PAUSE_MS / 1000).toString(), "s")
        row("meta", "", "cooldown_maximum", (MAX_COOLDOWN_MS / 1000).toString(), "s")
        row("meta", "", "cooldown_target_margin", num(COOL_MARGIN_C), "C above benchmark-start temperature")
        row("meta", "", "perf_cores", fastCores.joinToString(" "), "cpu index")
        row("meta", "", "little_cores", littleCores.joinToString(" "), "cpu index")
        row("meta", "", "cpu_max_clocks", maxFreqsKhz.joinToString(" ") { (it / 1000).toString() }, "MHz per cpu index")

        for (c in r.configs) {
            c.runs.forEachIndexed { i, p ->
                val idx = (i + 1).toString()
                row(c.key, idx, "pp", num(p.pp), "tok/s")
                row(c.key, idx, "tg", num(p.tg), "tok/s")
                row(c.key, idx, "power", num(p.watts), "W")
                row(c.key, idx, "tok_per_w", num(p.tokPerW), "tok/W")
                row(c.key, idx, "ttft_pp${PP}_derived", num(p.ttftMs), "ms")
                row(c.key, idx, "start_temp", num(p.startTempC), "C")
                row(c.key, idx, "average_process_cpu", num(p.averageProcessCpuPercent), "% one core")
                row(c.key, idx, "minimum_free_ram", num(p.minimumFreeGb), "GiB")
                row(c.key, idx, "peak_battery_temp", num(p.peakBatteryTempC), "C")
                row(c.key, idx, "peak_thermal_status", p.peakThermalStatus.toString(), "Android PowerManager status")
                p.telemetry.forEachIndexed { sampleIndex, sample ->
                    val sampleRun = "$idx:${sampleIndex + 1}"
                    row(c.key, sampleRun, "sample_elapsed", sample.elapsedMs.toString(), "ms")
                    row(c.key, sampleRun, "sample_power", num(sample.watts), "W")
                    row(c.key, sampleRun, "sample_process_cpu", num(sample.processCpuPercent), "% one core")
                    row(c.key, sampleRun, "sample_free_ram", num(sample.freeGb), "GiB")
                    row(c.key, sampleRun, "sample_battery_temp", num(sample.batteryTempC), "C")
                    row(c.key, sampleRun, "sample_thermal_status", sample.thermalStatus.toString(), "Android PowerManager status")
                    sample.cpuFreqMhz.forEachIndexed { cpu, mhz ->
                        if (mhz > 0) row(c.key, sampleRun, "sample_cpu${cpu}_freq", mhz.toString(), "MHz")
                    }
                }
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
            agg("average_process_cpu", "% one core") { it.averageProcessCpuPercent }
            agg("minimum_free_ram", "GiB") { it.minimumFreeGb }
            agg("peak_battery_temp", "C") { it.peakBatteryTempC }
            agg("mean_perf_core_clock", "MHz") { it.meanFastCoreFreqMhz(fastCores) }
            agg("mean_little_core_clock", "MHz") { it.meanLittleCoreFreqMhz(littleCores) }
        }
    }

    private fun buildSustainedCsv(r: SustainedResult): String = buildString {
        fun esc(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
        fun row(config: String, runIndex: String, metric: String, value: String, unit: String) =
            appendLine(listOf(config, runIndex, metric, value, unit).joinToString(",") { esc(it) })
        fun num(x: Double) = String.format(Locale.US, "%.3f", x)

        appendLine("config,run_index,metric,value,unit")
        row("meta", "", "app", "ENTITY", "")
        row("meta", "", "app_version", BuildConfig.VERSION_NAME, "")
        row("meta", "", "test", "sustained_no_cooldown", "")
        row("meta", "", "model", modelTv.text.toString(), "")
        row("meta", "", "device", "${Build.MANUFACTURER} ${Build.MODEL}", "")
        row("meta", "", "device_fingerprint", Build.FINGERPRINT, "")
        row("meta", "", "passes_per_config", SUSTAINED_PASSES.toString(), "")
        row("meta", "", "inter_pass_gap", (SUSTAINED_GAP_MS / 1000).toString(), "s")
        row("meta", "", "pp", PP.toString(), "tokens")
        row("meta", "", "tg", TG.toString(), "tokens")
        row("meta", "", "perf_cores", fastCores.joinToString(" "), "cpu index")
        row("meta", "", "little_cores", littleCores.joinToString(" "), "cpu index")
        for (c in listOf(r.threadsOnly, r.opt)) {
            row("meta", "", "threads_${c.key}", c.threads.toString(), "")
            row("meta", "", "affinity_${c.key}", if (c.pinned) "pinned_fast_cores" else "none_scheduler_placed", "")
            c.runs.forEachIndexed { i, p ->
                val idx = (i + 1).toString()
                row(c.key, idx, "tg", num(p.tg), "tok/s")
                row(c.key, idx, "start_temp", num(p.startTempC), "C")
                row(c.key, idx, "peak_battery_temp", num(p.peakBatteryTempC), "C")
                row(c.key, idx, "peak_thermal_status", p.peakThermalStatus.toString(), "Android PowerManager status")
                row(c.key, idx, "mean_perf_core_clock", num(p.meanFastCoreFreqMhz(fastCores)), "MHz")
                row(c.key, idx, "mean_little_core_clock", num(p.meanLittleCoreFreqMhz(littleCores)), "MHz")
            }
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
        // Sustained thermal test: enough back-to-back passes to build a visible trend,
        // with only a short gap - not a cooldown - between them.
        private const val SUSTAINED_PASSES = 6
        private const val SUSTAINED_GAP_MS = 2_000L
        private const val COOL_MARGIN_C = 0.5
        // Cooldown never waits below this — ambient in hot climates keeps batteries above ~37°C.
        private const val MIN_COOL_TARGET_C = 37.5
        private const val REQ_EXPORT_CSV = 41
        private const val STATE_PENDING_CSV = "pending_csv_path"
    }
}
