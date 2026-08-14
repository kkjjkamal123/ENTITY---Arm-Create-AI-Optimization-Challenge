package com.entity.bench

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.arm.aichat.InferenceEngine
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

/**
 * The measurement core. Runs the same synthetic PP/TG benchmark for each arm with a
 * thermal cooldown before every pass, sampling power, temperature, thermal status,
 * process CPU and per-core clocks throughout, so a result shows speed AND energy
 * efficiency - the axis other on-device benchmarks skip.
 *
 * The three ablation arms:
 *   naive        8 threads, every core, default scheduler
 *   threads_only the tuned thread count, no affinity (an upstream llama.cpp -t N run)
 *   optimized    the tuned count pinned to the performance cluster
 *
 * naive -> threads_only isolates the thread count; threads_only -> optimized isolates
 * the core placement. The optional efficiency arm inverts the placement to the slow
 * cluster and answers a tok/W question, not a speed one.
 *
 * The sustained mode runs threads_only vs optimized back-to-back with no cooldown
 * inside a block, so heat accumulates - it shows who throttles first.
 */
class BenchRunner(private val context: Context, private val engine: InferenceEngine) {

    // All three fire on background dispatchers; the caller marshals to the UI.
    var onStatus: (String) -> Unit = {}
    var onProgress: (Double) -> Unit = {}
    var onLive: (TelemetrySample) -> Unit = {}

    private val batteryManager get() =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager get() =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val maxFreqsKhz by lazy { DeviceInfo.maxFreqsKhz() }
    private val fastCores by lazy { DeviceInfo.fastCoreIndices(maxFreqsKhz) }
    private val littleCores by lazy { maxFreqsKhz.indices.filter { it !in fastCores.toSet() } }

    val hasLittleCores get() = littleCores.isNotEmpty()

    private var progressDone = 0
    private var progressTotal = 1

    // ---- model loading ----

    suspend fun loadModel(model: File) {
        val state = engine.state.value
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
            runCatching { engine.cleanUp() }
        }
        val ctx = adaptiveContext(model)
        engine.applyConfig(ctx, THREADS_AUTO, TEMP, TOP_K, TOP_P)
        engine.loadModel(model.path)
        activeCtx = ctx
    }

    private var activeCtx = DEF_CTX

    // Context sized to the model and the memory free right now, mirroring what the
    // chat app's auto mode does. A benchmark should measure the shipped configuration.
    private fun adaptiveContext(model: File): Int {
        val sizeGb = model.length() / 1_000_000_000.0
        val mem = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val freeGb = mem.availMem / 1_073_741_824.0
        return when {
            sizeGb < 1.6 -> if (freeGb > 3.0) 8192 else 4096
            else -> if (freeGb > 2.2) 4096 else 2048
        }
    }

    // ---- the two benchmark types ----

    suspend fun runAblation(modelName: String, nRuns: Int, efficiencyArm: Boolean,
                            adpfEnabled: Boolean = true): BenchResult {
        resetChargingWatch()
        val baselineC = readTempC()
        val startThermal = powerManager.currentThermalStatus
        val coolTargetC = coolTarget(baselineC)
        val withEfficiency = efficiencyArm && littleCores.isNotEmpty()
        val adpfArm = adpfEnabled
        progressDone = 0
        progressTotal = 1 + ((if (withEfficiency) 4 else 3) + (if (adpfArm) 1 else 0)) * nRuns
        try {
            status("warming up (discarded pass)")
            engine.applyConfig(activeCtx, THREADS_AUTO, TEMP, TOP_K, TOP_P)
            engine.bench(64, 16, PL, 1)   // discarded - pages in weights, warms caches
            tick()

            // Ablation order: naive -> threads_only -> optimized. Every arm gets the
            // same cooldown, so the order does not favour the last one.
            val naive = runArm("naive", "naive", NAIVE_THREADS, true, nRuns, coolTargetC)
            val threadsOnly = runArm("threads-only", "threads_only", autoGenThreads(), false, nRuns, coolTargetC)
            val opt = runArm("auto", "optimized", THREADS_AUTO, true, nRuns, coolTargetC)
            val efficiency = if (withEfficiency) {
                val effThreads = littleCores.size.coerceAtMost(autoGenThreads())
                runArm("efficiency", "efficiency", effThreads, true, nRuns, coolTargetC, pinEfficiency = true)
            } else null

            // ADPF: Auto's thread count, affinity OFF, but the platform is told the
            // deadline for each decode step. Deliberately unpinned - it is the
            // alternative to pinning, not an addition to it, so the comparison that
            // matters is adpf vs threads_only (same width, neither pinned) and adpf vs
            // optimized (same width, hint instead of a hard mask).
            val adpf = if (adpfArm) {
                runArm("adpf", "adpf", autoGenThreads(), false, nRuns, coolTargetC, adpf = true)
            } else null

            val arms = listOfNotNull(naive, threadsOnly, opt, efficiency, adpf)
            if (arms.any { a -> stat(a.passes.map { it.tg }).n == 0 }) {
                error("Engine returned no timing - try again.")
            }
            return result(BenchResult.TYPE_ABLATION, modelName, chargingSeen, baselineC, startThermal,
                runsPerArm = nRuns, durationMin = 0, arms = arms)
        } finally {
            restoreConfig()
        }
    }

    fun sweepThreadCounts(): List<Int> = DeviceInfo.sweepThreadCounts(maxFreqsKhz)

    /**
     * Every thread width, each one both pinned and scheduler-placed. The ablation asks
     * "does the shipped policy beat the default"; this asks the harder question the
     * ablation cannot - "is the shipped policy the best this phone can do", and it
     * answers it per device instead of from a table that ages with every new SoC.
     *
     * Pinning an explicit thread count masks to exactly that many of the fastest cores,
     * so each row is a width AND its placement, and the pinned/no-pin pair at a fixed
     * width isolates placement the same way threads-only -> auto does.
     */
    suspend fun runSweep(modelName: String, nRuns: Int): BenchResult {
        resetChargingWatch()
        val baselineC = readTempC()
        val startThermal = powerManager.currentThermalStatus
        val coolTargetC = coolTarget(baselineC)
        val counts = sweepThreadCounts()
        progressDone = 0
        progressTotal = 1 + counts.size * 2 * nRuns
        try {
            status("warming up (discarded pass)")
            engine.applyConfig(activeCtx, THREADS_AUTO, TEMP, TOP_K, TOP_P)
            engine.bench(64, 16, PL, 1)   // discarded - pages in weights, warms caches
            tick()

            val arms = ArrayList<Arm>()
            for (t in counts) {
                arms += runArm("$t threads, pinned", "sweep_t${t}_pinned", t, true, nRuns, coolTargetC)
                arms += runArm("$t threads, no pin", "sweep_t${t}_nopin", t, false, nRuns, coolTargetC)
            }
            if (arms.any { a -> stat(a.passes.map { it.tg }).n == 0 }) {
                error("Engine returned no timing - try again.")
            }
            return result(BenchResult.TYPE_SWEEP, modelName, chargingSeen, baselineC, startThermal,
                runsPerArm = nRuns, durationMin = 0, arms = arms)
        } finally {
            restoreConfig()
        }
    }

    suspend fun runSustained(modelName: String, durationMs: Long): BenchResult {
        resetChargingWatch()
        val baselineC = readTempC()
        val startThermal = powerManager.currentThermalStatus
        val coolTargetC = coolTarget(baselineC)
        try {
            status("warming up (discarded pass)")
            engine.applyConfig(activeCtx, THREADS_AUTO, TEMP, TOP_K, TOP_P)
            engine.bench(64, 16, PL, 1)
            onProgress(0.02)

            val threadsOnly = runSustainedArm("threads-only", "threads_only", autoGenThreads(), false,
                coolTargetC, durationMs, blockIndex = 0)
            val opt = runSustainedArm("auto", "optimized", THREADS_AUTO, true,
                coolTargetC, durationMs, blockIndex = 1)
            if (threadsOnly.passes.isEmpty() || opt.passes.isEmpty()) {
                error("Engine returned no timing - try again.")
            }
            return result(BenchResult.TYPE_SUSTAINED, modelName, chargingSeen, baselineC, startThermal,
                runsPerArm = 0, durationMin = (durationMs / 60_000L).toInt(),
                arms = listOf(threadsOnly, opt))
        } finally {
            restoreConfig()
        }
    }

    private fun result(
        type: String, modelName: String, charging: Boolean, startTempC: Double,
        startThermal: Int, runsPerArm: Int, durationMin: Int, arms: List<Arm>,
    ) = BenchResult(
        type = type,
        ts = System.currentTimeMillis(),
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        model = modelName,
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        deviceFingerprint = Build.FINGERPRINT,
        androidRelease = Build.VERSION.RELEASE ?: "",
        androidSdk = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        charging = charging,
        startTempC = startTempC,
        startThermalStatus = startThermal,
        runsPerArm = runsPerArm,
        durationMin = durationMin,
        fastCores = fastCores,
        littleCores = littleCores,
        maxFreqsMhz = maxFreqsKhz.map { (it / 1000).toInt() },
        cpuCapacities = DeviceInfo.cpuCapacities().map { it.toInt() },
        arms = arms,
    )

    private suspend fun restoreConfig() {
        withContext(NonCancellable) {
            runCatching {
                engine.applyConfig(activeCtx, THREADS_AUTO, TEMP, TOP_K, TOP_P,
                    pinCores = true, pinEfficiency = false)
            }
        }
    }

    // ---- arms ----

    private suspend fun runArm(
        label: String,
        key: String,
        threads: Int,
        pinCores: Boolean,
        nRuns: Int,
        coolTargetC: Double,
        pinEfficiency: Boolean = false,
        adpf: Boolean = false,
    ): Arm {
        // 0 threads = auto, exactly what the chat app ships. pinCores = false is the
        // ablation arm: same thread count, scheduler-placed, no pinned pool.
        engine.applyConfig(activeCtx, threads, TEMP, TOP_K, TOP_P, pinCores, pinEfficiency, adpf)
        val genThreads = if (threads <= 0) autoGenThreads() else threads
        val passes = ArrayList<Pass>(nRuns)
        for (i in 1..nRuns) {
            val placement = when {
                adpf -> "adpf"
                pinEfficiency -> "slow pinned"
                pinCores -> "pinned"
                else -> "no pin"
            }
            val prefix = "$label / $genThreads threads, $placement / pass $i of $nRuns"
            cooldown(prefix, coolTargetC)
            val startTempC = readTempC()
            Log.i(TAG, String.format(Locale.US,
                "%s run %d/%d start: threads=%d pinned=%b slowCluster=%b battery=%.1fC thermalStatus=%d",
                key, i, nRuns, genThreads, pinCores, pinEfficiency, startTempC,
                powerManager.currentThermalStatus))
            status(prefix)
            passes.add(runPass(startTempC))
            tick()
        }
        return Arm(label, key, genThreads, pinCores, pinEfficiency, passes)
    }

    // No cooldown between passes inside a block: heat is meant to accumulate. Both
    // blocks start from the same cooled baseline, so pass 1 is comparable across arms.
    private suspend fun runSustainedArm(
        label: String,
        key: String,
        threads: Int,
        pinCores: Boolean,
        coolTargetC: Double,
        durationMs: Long,
        blockIndex: Int,
    ): Arm {
        engine.applyConfig(activeCtx, threads, TEMP, TOP_K, TOP_P, pinCores)
        val genThreads = if (threads <= 0) autoGenThreads() else threads
        cooldown("$label / cooling to baseline before the block", coolTargetC)
        val passes = ArrayList<Pass>()
        val blockStart = SystemClock.elapsedRealtime()
        val totalMin = (durationMs / 60_000L).toInt()
        var i = 0
        while (true) {
            i++
            val elapsed = SystemClock.elapsedRealtime() - blockStart
            onProgress((blockIndex + (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)) / 2.0)
            val mmss = (elapsed / 1000).let { "%d:%02d".format(it / 60, it % 60) }
            status("$label / ${genThreads}t ${if (pinCores) "pinned" else "no pin"} / sustained pass $i ($mmss of ${totalMin}min)")
            val startTempC = readTempC()
            Log.i(TAG, String.format(Locale.US,
                "sustained %s pass %d start: threads=%d pinned=%b battery=%.1fC thermalStatus=%d",
                key, i, genThreads, pinCores, startTempC, powerManager.currentThermalStatus))
            passes.add(runPass(startTempC))
            if (SystemClock.elapsedRealtime() - blockStart >= durationMs) break
            delay(SUSTAINED_GAP_MS)
        }
        onProgress((blockIndex + 1.0) / 2.0)
        return Arm(label, key, genThreads, pinCores, false, passes)
    }

    // Generation threads the native side derives in auto mode - mirrors init_context()
    // in ai_chat.cpp: the top frequency cluster's core count, clamped to [2, 6].
    private fun autoGenThreads() = DeviceInfo.topClusterCoreCount(maxFreqsKhz)

    private suspend fun cooldown(prefix: String, targetC: Double) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            val t = readTempC()
            val cooled = t <= 0.0 || targetC <= 0.0 || t <= targetC
            if (elapsed >= MIN_PAUSE_MS && (cooled || elapsed >= MAX_COOLDOWN_MS)) break
            status(
                if (!cooled) {
                    "$prefix\ncooling ${"%.1f".format(t)}C -> ${"%.1f".format(targetC)}C"
                } else {
                    "$prefix\ncooling ${((MIN_PAUSE_MS - elapsed) / 1000 + 1).coerceAtLeast(1)}s"
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
                // Polled on the power clock, not once per run: this is the only place that
                // can notice a charger arriving mid-pass, which is exactly when it would
                // silently contaminate every watts reading that follows.
                pollCharging()
                val ua = readCurrentUa()
                val watts = ua?.let { PowerMath.watts(it, voltage) } ?: 0.0
                if (watts > 0.0) samples.add(watts)
                val processCpuMs = android.os.Process.getElapsedCpuTime()
                val elapsedMs = now - lastCpuWallMs
                val processCpuPercent = if (elapsedMs > 0L) {
                    (processCpuMs - lastProcessCpuMs).coerceAtLeast(0L) * 100.0 / elapsedMs
                } else 0.0
                val sample = TelemetrySample(
                    elapsedMs = now - benchmarkStartMs,
                    watts = watts,
                    freeGb = availableGb(),
                    batteryTempC = readTempC(),
                    thermalStatus = powerManager.currentThermalStatus,
                    processCpuPercent = processCpuPercent,
                    cpuFreqMhz = DeviceInfo.currentFreqsKhz().map { (it / 1000).toInt() },
                )
                telemetry.add(sample)
                onLive(sample)
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

    // Pull the t/s number out of benchModel's markdown table row (| ... | pp 512 | 18.4 ± 0 |).
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

    // ---- device readers ----

    private fun coolTarget(baselineC: Double) =
        if (baselineC > 0.0) maxOf(baselineC + COOL_MARGIN_C, MIN_COOL_TARGET_C) else 0.0

    private fun readCurrentUa(): Long? {
        val v = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (v == Long.MIN_VALUE || v == 0L) null else v
    }

    private fun readVoltageMv(): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    }

    private fun readTempC(): Double {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths < 0) 0.0 else tenths / 10.0
    }

    private fun availableGb(): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024.0 * 1024.0 * 1024.0)
    }

    /**
     * True if the phone was charging at any point since [resetChargingWatch].
     *
     * The charging flag used to be a single reading taken before the first pass, and it is
     * what `ResultUploader` turns into `power_valid` for the public dataset. A charger
     * plugged or unplugged a minute into a multi-minute run never reached it, while
     * `PowerMath.watts()` went on stripping the sign of every subsequent sample - so a run
     * contaminated by charger current could be uploaded as clean, or a clean run marked
     * invalid, and neither is recoverable from the stored result.
     *
     * Sticky, and deliberately so: a run is contaminated if it was charging for any part
     * of its duration, not if it happened to be charging when someone looked. The sampler
     * polls it every 150 ms alongside power, which is the same clock the contamination
     * would arrive on.
     */
    private var chargingSeen = false

    private fun resetChargingWatch() {
        chargingSeen = false
        pollCharging()
    }

    private fun pollCharging(): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val now = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (now) chargingSeen = true
        return chargingSeen
    }

    private fun status(text: String) = onStatus(text)

    private fun tick() {
        progressDone++
        onProgress(progressDone.toDouble() / progressTotal)
    }

    companion object {
        private const val TAG = "EntityBench"
        const val PP = 512
        const val TG = 128
        const val PL = 1
        private const val NR = 1
        // 0 = auto: the engine picks the generation threads, pins them to the fastest
        // cores and widens prompt processing to all cores - the shipped configuration.
        private const val THREADS_AUTO = 0
        const val NAIVE_THREADS = 8
        const val MIN_PAUSE_MS = 15_000L
        const val MAX_COOLDOWN_MS = 90_000L
        const val SUSTAINED_GAP_MS = 2_000L
        const val COOL_MARGIN_C = 0.5
        // Cooldown never waits below this - ambient in hot climates keeps batteries above ~37C.
        const val MIN_COOL_TARGET_C = 37.5
        private const val DEF_CTX = 4096
        // Sampler settings are irrelevant to bench() but applyConfig wants them.
        private const val TEMP = 0.3f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
    }
}
