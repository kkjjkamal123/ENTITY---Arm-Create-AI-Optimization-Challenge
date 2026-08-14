package com.entity.bench

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Optional, opt-in contribution of a finished benchmark to the public ENTITY dataset.
 *
 * The point is not telemetry. ENTITY's central claim - that thread count earns the decode
 * multiplier and core pinning is device-dependent - was measured on two phones. It is only
 * a general claim if it holds on silicon the author has never touched, so the app that runs
 * the experiment can also return the answer.
 *
 * Design constraints this file exists to satisfy:
 *  - **Off unless the user turns it on.** No first-run upload, no "anonymous statistics"
 *    default. [enabled] is false until someone taps the toggle.
 *  - **Nothing is sent that the user has not been shown.** [payload] is the exact body,
 *    and Settings renders it verbatim before the first upload.
 *  - **No persistent identifier.** Each submission carries a fresh random id for
 *    de-duplication only; nothing links two submissions from the same phone.
 *  - **Disabled unless configured.** The endpoint is a build config value that is blank in
 *    the public source, so a fork builds and runs with contribution simply switched off
 *    rather than posting into someone else's database.
 *
 * Transport is a single HTTPS POST via HttpURLConnection - the same primitive
 * [ModelDownloader] uses - so no HTTP client, analytics SDK or backend library is linked
 * into the APK. The server side is PostgREST over one Postgres table with an insert-only
 * row-level-security policy, so the key embedded here can only append rows; it cannot read,
 * update or delete anything.
 */
object ResultUploader {

    /** True when this build was given an endpoint at compile time. */
    val configured: Boolean
        get() = BuildConfig.RESULTS_ENDPOINT.isNotBlank() && BuildConfig.RESULTS_KEY.isNotBlank()

    fun enabled(ctx: Context): Boolean =
        configured && Prefs.get(ctx).getBoolean(Prefs.KEY_CONTRIBUTE, Prefs.DEF_CONTRIBUTE)

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.get(ctx).edit().putBoolean(Prefs.KEY_CONTRIBUTE, on).apply()
    }

    /**
     * The complete body that would be sent for [r]. Summary statistics only - the per-pass
     * 150 ms telemetry stays on the phone, because the dataset needs medians per arm, not
     * a second-by-second trace of someone's device.
     */
    fun payload(ctx: Context, r: BenchResult): JSONObject {
        val flags = DeviceInfo.readCpuFlags()
        val quant = Regex("(Q\\d+_[0-9KMSL]+(?:_[A-Z])?|F16|BF16|F32)", RegexOption.IGNORE_CASE)
            .find(r.model)?.value?.uppercase()

        val arms = JSONArray()
        for (a in r.arms) {
            val pp = stats(a.passes.map { it.pp })
            val tg = stats(a.passes.map { it.tg })
            val w = stats(a.passes.map { it.watts })
            val eff = stats(a.passes.map { it.tokPerW })
            val ttft = stats(a.passes.map { it.ttftMs })
            arms.put(JSONObject().apply {
                put("arm", a.key)
                put("threads", a.threads)
                put("pinned", a.pinned)
                put("slow_cluster", a.slowCluster)
                put("passes", a.passes.size)
                put("prompt_tok_s", pp.median)
                put("prompt_sd", pp.sd)
                put("decode_tok_s", tg.median)
                put("decode_sd", tg.sd)
                put("ttft_ms", ttft.median)
                put("watts", w.median)
                put("tok_per_w", eff.median)
            })
        }

        return JSONObject().apply {
            // Fresh per submission: lets the dataset drop accidental duplicates without
            // ever linking two runs to the same phone.
            put("submission_id", UUID.randomUUID().toString())
            put("app_version", r.appVersion)
            put("app_version_code", r.appVersionCode)
            put("run_type", r.type)
            put("run_ts", r.ts)

            put("device_manufacturer", r.deviceManufacturer)
            put("device_model", r.deviceModel)
            put("soc", "${Build.HARDWARE} / ${Build.SOC_MODEL}")
            put("android_release", r.androidRelease)
            put("android_sdk", r.androidSdk)
            put("abis", JSONArray(r.abis))
            put("cpu_flags", JSONArray(flags.toList()))
            put("max_freqs_mhz", JSONArray(r.maxFreqsMhz))
            put("cpu_capacities", JSONArray(r.cpuCapacities))
            put("fast_cores", r.fastCores.size)
            put("little_cores", r.littleCores.size)

            put("model_file", r.model)
            put("quantization", quant ?: JSONObject.NULL)
            put("kleidiai_accelerated", quant == "Q4_0" || quant == "Q8_0")

            put("runs_per_arm", r.runsPerArm)
            put("duration_min", r.durationMin)
            put("start_temp_c", r.startTempC)

            // The single most important honesty flag in the whole payload: a charging phone
            // reports the charger's current, not the workload's, so its power and tok/W
            // columns are meaningless and must never be averaged into the dataset.
            put("charging", r.charging)
            put("power_valid", !r.charging)

            put("arms", arms)
        }
    }

    private data class S(val median: Double, val sd: Double)

    private fun stats(xs: List<Double>): S {
        val v = xs.filter { it > 0.0 }
        if (v.isEmpty()) return S(0.0, 0.0)
        val s = v.sorted()
        val median = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        val mean = v.average()
        val sd = kotlin.math.sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        return S(median, sd)
    }

    /**
     * Posts [r]. Returns true on success. A failure is not an error the user needs to see -
     * the result is already saved on the phone - so the body is queued and retried the next
     * time the app starts. A benchmark is usually run unplugged and away from a network.
     */
    suspend fun upload(ctx: Context, r: BenchResult, file: String? = null): Boolean {
        // The automatic path carries its own consent check, now that [post] no longer does
        // one for everybody. Nothing is queued when contribution is off either: a result
        // the user did not agree to share must not sit on disk waiting to be sent the day
        // they switch the toggle on for something else.
        if (!enabled(ctx)) return false
        val body = payload(ctx, r).toString()
        val ok = post(ctx, body)
        if (ok) file?.let { markSent(ctx, it) } else queue(ctx, body)
        return ok
    }

    /** Retry anything queued from an earlier offline run. Safe to call on every launch. */
    suspend fun flushQueue(ctx: Context) {
        if (!enabled(ctx)) return
        val dir = queueDir(ctx)
        for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (post(ctx, f.readText())) f.delete() else return   // still offline; stop early
        }
    }

    fun queuedCount(ctx: Context): Int = queueDir(ctx).listFiles()?.size ?: 0

    // ---- what has already been contributed ----
    //
    // Kept as a set of result file names rather than a flag inside the result, so the
    // saved JSON stays exactly what the benchmark produced and re-sending is idempotent
    // from the user's point of view as well as the server's (submission_id is unique).

    fun sentFiles(ctx: Context): Set<String> =
        Prefs.get(ctx).getStringSet(Prefs.KEY_SENT_RESULTS, emptySet()) ?: emptySet()

    fun isSent(ctx: Context, file: String) = file in sentFiles(ctx)

    private fun markSent(ctx: Context, file: String) {
        Prefs.get(ctx).edit()
            .putStringSet(Prefs.KEY_SENT_RESULTS, sentFiles(ctx) + file)
            .apply()
    }

    /**
     * Sends one already-saved result by file name. This is the path the Share screen uses;
     * [upload] is the automatic one that runs when a benchmark finishes.
     */
    suspend fun uploadSaved(ctx: Context, file: String): Boolean {
        val r = withContext(Dispatchers.IO) { ResultStore.load(ctx, file) } ?: return false
        val ok = post(ctx, payload(ctx, r).toString())
        if (ok) markSent(ctx, file)
        return ok
    }

    private fun queueDir(ctx: Context) = File(ctx.filesDir, "upload_queue").apply { mkdirs() }

    private fun queue(ctx: Context, body: String) {
        runCatching { File(queueDir(ctx), "${System.currentTimeMillis()}.json").writeText(body) }
    }

    /**
     * The transport. Deliberately checks [configured] and not [enabled].
     *
     * Consent belongs to the caller, because the two callers have different consent. The
     * automatic path ([upload], [flushQueue]) fires without anyone asking for it, so it is
     * gated on the Settings toggle. The Contribute screen is the opposite: the user opened
     * a picker, chose specific results and tapped Send, which is consent for those results
     * and does not depend on a global preference they may never have touched.
     *
     * Requiring the toggle here made that screen fail for exactly the users it was built
     * for - every send returned a generic error immediately after an explicit choice to
     * share.
     */
    private suspend fun post(ctx: Context, body: String): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        runCatching {
            val conn = (URL(BuildConfig.RESULTS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.RESULTS_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.RESULTS_KEY}")
                // Insert-only: ask PostgREST not to echo the row back, so no SELECT policy
                // is needed and the key stays strictly append-only.
                setRequestProperty("Prefer", "return=minimal")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
