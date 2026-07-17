package com.entity.bench

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// One telemetry snapshot, sampled every ~150 ms during a pass. App-process CPU can
// exceed 100% when llama.cpp uses more than one core. cpuFreqMhz index = cpu number,
// 0 where the kernel hides scaling_cur_freq.
data class TelemetrySample(
    val elapsedMs: Long,
    val watts: Double,
    val freeGb: Double,
    val batteryTempC: Double,
    val thermalStatus: Int,
    val processCpuPercent: Double,
    val cpuFreqMhz: List<Int>,
)

data class Pass(
    val pp: Double,
    val tg: Double,
    val watts: Double,
    val tokPerW: Double,
    val ttftMs: Double,
    val startTempC: Double,
    val telemetry: List<TelemetrySample>,
) {
    private val usableTelemetry get() = telemetry.drop(1)
    val averageProcessCpuPercent get() = usableTelemetry.let { s ->
        if (s.isEmpty()) 0.0 else s.map { it.processCpuPercent }.average()
    }
    val minimumFreeGb get() = telemetry.map { it.freeGb }.filter { it > 0.0 }.minOrNull() ?: 0.0
    val peakBatteryTempC get() = telemetry.maxOfOrNull { it.batteryTempC } ?: startTempC
    val peakThermalStatus get() = telemetry.maxOfOrNull { it.thermalStatus } ?: 0

    // Mean live clock of the given cores across the pass. A pinned decode holds the
    // performance cores near their ceiling; an unpinned one lets work drift and the
    // mean sags. 0 when the kernel hides scaling_cur_freq.
    private fun meanFreq(indices: List<Int>) = usableTelemetry
        .flatMap { s -> indices.mapNotNull { s.cpuFreqMhz.getOrNull(it) } }
        .filter { it > 0 }
        .let { if (it.isEmpty()) 0.0 else it.average() }

    fun meanFastCoreFreqMhz(fast: List<Int>) = meanFreq(fast)
    fun meanLittleCoreFreqMhz(little: List<Int>) = meanFreq(little)
}

data class Arm(
    val label: String,
    val key: String,        // naive | threads_only | optimized | efficiency
    val threads: Int,
    val pinned: Boolean,
    val slowCluster: Boolean,
    val passes: List<Pass>,
)

data class Stat(val median: Double, val sd: Double, val n: Int)

fun stat(xs: List<Double>): Stat {
    val v = xs.filter { it > 0.0 }
    if (v.isEmpty()) return Stat(0.0, 0.0, 0)
    val s = v.sorted()
    val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    val mean = v.average()
    val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
    return Stat(median, sd, v.size)
}

// A complete saved benchmark. Everything the result page and the CSV export need is
// in here, so a result stays fully readable and exportable long after the run.
data class BenchResult(
    val type: String,                  // TYPE_ABLATION | TYPE_SUSTAINED
    val ts: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val model: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceFingerprint: String,
    val androidRelease: String,
    val androidSdk: Int,
    val abis: List<String>,
    val charging: Boolean,
    val startTempC: Double,
    val startThermalStatus: Int,
    val runsPerArm: Int,               // ablation only
    val durationMin: Int,              // sustained only
    val fastCores: List<Int>,
    val littleCores: List<Int>,
    val maxFreqsMhz: List<Int>,
    val arms: List<Arm>,
) {
    val naive get() = arms.firstOrNull { it.key == "naive" }
    val threadsOnly get() = arms.firstOrNull { it.key == "threads_only" }
    val optimized get() = arms.firstOrNull { it.key == "optimized" }
    val efficiency get() = arms.firstOrNull { it.key == "efficiency" }

    // The three-arm ablation the table and its delta column are built from; the
    // efficiency arm rides alongside and never enters this list.
    val ablationArms get() = listOfNotNull(naive, threadsOnly, optimized)

    val powerValid get() = !charging &&
        arms.all { a -> stat(a.passes.map { it.watts }).n > 0 }

    fun decodeDeltaPct(): Double {
        val n = stat(naive?.passes?.map { it.tg } ?: emptyList()).median
        val o = stat(optimized?.passes?.map { it.tg } ?: emptyList()).median
        return if (n > 0) (o / n - 1) * 100 else 0.0
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("type", type)
        put("ts", ts)
        put("app_version", appVersion)
        put("app_version_code", appVersionCode)
        put("model", model)
        put("device_manufacturer", deviceManufacturer)
        put("device_model", deviceModel)
        put("device_fingerprint", deviceFingerprint)
        put("android_release", androidRelease)
        put("android_sdk", androidSdk)
        put("abis", JSONArray(abis))
        put("charging", charging)
        put("start_temp_c", startTempC)
        put("start_thermal_status", startThermalStatus)
        put("runs_per_arm", runsPerArm)
        put("duration_min", durationMin)
        put("fast_cores", JSONArray(fastCores))
        put("little_cores", JSONArray(littleCores))
        put("max_freqs_mhz", JSONArray(maxFreqsMhz))
        put("arms", JSONArray().also { arr ->
            arms.forEach { a ->
                arr.put(JSONObject().apply {
                    put("label", a.label)
                    put("key", a.key)
                    put("threads", a.threads)
                    put("pinned", a.pinned)
                    put("slow_cluster", a.slowCluster)
                    put("passes", JSONArray().also { ps ->
                        a.passes.forEach { p ->
                            ps.put(JSONObject().apply {
                                put("pp", p.pp)
                                put("tg", p.tg)
                                put("watts", p.watts)
                                put("tok_per_w", p.tokPerW)
                                put("ttft_ms", p.ttftMs)
                                put("start_temp_c", p.startTempC)
                                // Compact column arrays instead of one object per sample:
                                // telemetry dominates the file size.
                                put("t_elapsed_ms", JSONArray(p.telemetry.map { it.elapsedMs }))
                                put("t_watts", JSONArray(p.telemetry.map { it.watts }))
                                put("t_free_gb", JSONArray(p.telemetry.map { it.freeGb }))
                                put("t_batt_c", JSONArray(p.telemetry.map { it.batteryTempC }))
                                put("t_thermal", JSONArray(p.telemetry.map { it.thermalStatus }))
                                put("t_cpu_pct", JSONArray(p.telemetry.map { it.processCpuPercent }))
                                put("t_freqs", JSONArray(p.telemetry.map { JSONArray(it.cpuFreqMhz) }))
                            })
                        }
                    })
                })
            }
        })
    }

    companion object {
        const val TYPE_ABLATION = "ablation"
        const val TYPE_SUSTAINED = "sustained"

        fun fromJson(o: JSONObject): BenchResult {
            fun ints(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getInt(it) }
            fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            val arms = ArrayList<Arm>()
            val armsJson = o.optJSONArray("arms") ?: JSONArray()
            for (i in 0 until armsJson.length()) {
                val a = armsJson.getJSONObject(i)
                val passes = ArrayList<Pass>()
                val passesJson = a.optJSONArray("passes") ?: JSONArray()
                for (j in 0 until passesJson.length()) {
                    val p = passesJson.getJSONObject(j)
                    val el = p.optJSONArray("t_elapsed_ms") ?: JSONArray()
                    val w = p.optJSONArray("t_watts")
                    val fg = p.optJSONArray("t_free_gb")
                    val bc = p.optJSONArray("t_batt_c")
                    val th = p.optJSONArray("t_thermal")
                    val cp = p.optJSONArray("t_cpu_pct")
                    val fq = p.optJSONArray("t_freqs")
                    val telemetry = (0 until el.length()).map { k ->
                        TelemetrySample(
                            elapsedMs = el.getLong(k),
                            watts = w?.optDouble(k, 0.0) ?: 0.0,
                            freeGb = fg?.optDouble(k, 0.0) ?: 0.0,
                            batteryTempC = bc?.optDouble(k, 0.0) ?: 0.0,
                            thermalStatus = th?.optInt(k, 0) ?: 0,
                            processCpuPercent = cp?.optDouble(k, 0.0) ?: 0.0,
                            cpuFreqMhz = ints(fq?.optJSONArray(k)),
                        )
                    }
                    passes.add(
                        Pass(
                            pp = p.optDouble("pp", 0.0),
                            tg = p.optDouble("tg", 0.0),
                            watts = p.optDouble("watts", 0.0),
                            tokPerW = p.optDouble("tok_per_w", 0.0),
                            ttftMs = p.optDouble("ttft_ms", 0.0),
                            startTempC = p.optDouble("start_temp_c", 0.0),
                            telemetry = telemetry,
                        )
                    )
                }
                arms.add(
                    Arm(
                        label = a.optString("label"),
                        key = a.optString("key"),
                        threads = a.optInt("threads"),
                        pinned = a.optBoolean("pinned"),
                        slowCluster = a.optBoolean("slow_cluster"),
                        passes = passes,
                    )
                )
            }
            return BenchResult(
                type = o.optString("type", TYPE_ABLATION),
                ts = o.optLong("ts"),
                appVersion = o.optString("app_version"),
                appVersionCode = o.optInt("app_version_code"),
                model = o.optString("model"),
                deviceManufacturer = o.optString("device_manufacturer"),
                deviceModel = o.optString("device_model"),
                deviceFingerprint = o.optString("device_fingerprint"),
                androidRelease = o.optString("android_release"),
                androidSdk = o.optInt("android_sdk"),
                abis = strings(o.optJSONArray("abis")),
                charging = o.optBoolean("charging"),
                startTempC = o.optDouble("start_temp_c", 0.0),
                startThermalStatus = o.optInt("start_thermal_status"),
                runsPerArm = o.optInt("runs_per_arm"),
                durationMin = o.optInt("duration_min"),
                fastCores = ints(o.optJSONArray("fast_cores")),
                littleCores = ints(o.optJSONArray("little_cores")),
                maxFreqsMhz = ints(o.optJSONArray("max_freqs_mhz")),
                arms = arms,
            )
        }
    }
}
