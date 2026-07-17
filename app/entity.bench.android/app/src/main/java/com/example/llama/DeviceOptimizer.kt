package com.example.llama

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File

// First-run "optimize for this device" suggestion. The decision itself is a pure
// function (no Android classes) so it stays JVM-testable, exactly like ThermalGuard;
// the Activity-side helpers below only gather the raw numbers and draw the dialog.
object DeviceOptimizer {

    data class Suggestion(val threads: Int, val contextSummary: String, val reason: String)

    // Mirrors the native clamp in ai_chat.cpp (N_THREADS_MIN / N_THREADS_MAX).
    const val MIN_THREADS = 2
    const val MAX_THREADS = 4

    // Auto tuning is the optimized path: the native side ranks cores by clock and pins
    // generation to the fast cluster, and MainActivity.adaptiveContext() sizes the window
    // per model and free RAM. So the suggestion DESCRIBES what auto will do; it never
    // freezes a context number, because no model is chosen on first run.
    fun suggest(fastCoreCount: Int): Suggestion {
        val threads = fastCoreCount.coerceIn(MIN_THREADS, MAX_THREADS)
        return Suggestion(
            threads = threads,
            contextSummary = "adaptive (2048–8192, sized per model and free memory)",
            reason = "Running $threads threads keeps generation on this phone's fastest cores, " +
                "and the context window is sized to each model and the memory free at the time.",
        )
    }

    // How many cores are NOT in the slowest frequency tier. Same idea as
    // ranked_fast_cpus() in ai_chat.cpp: rank by max clock, take the fast cluster.
    // ponytail: everything above the little cores counts as fast, so on a tri-cluster
    // chip the mid cores are included too — harmless, the count is clamped to 4 anyway.
    fun fastCoreCount(maxFreqsKhz: List<Long>): Int {
        val top = maxFreqsKhz.maxOrNull() ?: 0L
        // Nothing readable: fall back to half the cores.
        if (top <= 0L) return (maxFreqsKhz.size / 2).coerceAtLeast(1)
        val slowest = maxFreqsKhz.min()
        val fast = maxFreqsKhz.count { it > slowest }
        // All cores clock the same (no big.LITTLE split): they are all "fast".
        return if (fast == 0) maxFreqsKhz.size else fast
    }

    // Pull the human-readable ISA features out of llama's system-info string, which
    // reports the backend variant ggml actually dlopen'd for this CPU.
    fun cpuFeatures(systemInfo: String): List<String> {
        val on = systemInfo.split('|').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2 && kv[1].trim() == "1") kv[0].trim().substringAfterLast(' ').uppercase()
            else null
        }.toSet()
        // Token names as ggml's CPU backend reports them (ggml-cpu.cpp: MATMUL_INT8 is i8mm).
        val friendly = linkedMapOf(
            "SME" to "SME",
            "SVE" to "SVE",
            "MATMUL_INT8" to "i8mm",
            "DOTPROD" to "dotprod",
        )
        return friendly.filterKeys { it in on }.values.distinct()
    }

    fun show(activity: Activity, systemInfo: String, onApplied: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        val freqs = maxFreqsKhz()
        val fast = fastCoreCount(freqs)
        val little = (freqs.size - fast).coerceAtLeast(0)
        val topGhz = (freqs.maxOrNull() ?: 0L) / 1_000_000.0

        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val s = suggest(fast)

        val body = StringBuilder()
        body.append("Found $fast performance core${plural(fast)}")
        if (topGhz > 0.1) body.append(" at %.1f GHz".format(topGhz))
        body.append(" and $little efficiency core${plural(little)}, with ")
        body.append("%.1f GB of %.1f GB memory free.".format(gb(mem.availMem), gb(mem.totalMem)))
        val features = cpuFeatures(systemInfo)
        if (features.isNotEmpty()) {
            body.append(" Your CPU supports ${features.joinToString(", ")}, so ENTITY loaded its fastest matmul kernels for it.")
        }
        body.append("\n\nWhy it matters: pinning generation to your performance cores keeps it off the slower efficiency cores, so replies come faster and use less battery.")
        body.append("\n\nSuggested: run generation on this phone's ${s.threads} performance cores, with an adaptive context window sized per model and free memory (2048–8192 tokens).")
        body.append("\n\nOptimize keeps ENTITY's automatic tuning switched on and turns Efficiency mode off, so you get full speed. Your model choice is not touched.")

        AlertDialog.Builder(activity)
            .setTitle(R.string.optimize_title)
            .setMessage(body.toString())
            .setPositiveButton(R.string.optimize_apply) { _, _ ->
                // Auto IS the tuned path: threads = 0 lets the native side pin to the
                // frequency-ranked fast cores, and the context adapts per model.
                // Deliberately writes no THREADS/CTX override.
                prefs.edit()
                    .putBoolean(Settings.KEY_AUTO, true)
                    .putBoolean(Settings.KEY_EFFICIENCY, false)
                    .putBoolean(Settings.KEY_FIRST_RUN, true)
                    .apply()
                Toast.makeText(activity, R.string.optimize_applied, Toast.LENGTH_LONG).show()
                onApplied()
            }
            .setNegativeButton(R.string.optimize_not_now) { _, _ -> markDone(prefs) }
            .setOnCancelListener { markDone(prefs) }
            .show()
    }

    fun isFirstRun(prefs: SharedPreferences) =
        !prefs.getBoolean(Settings.KEY_FIRST_RUN, Settings.DEF_FIRST_RUN)

    private fun markDone(prefs: SharedPreferences) =
        prefs.edit().putBoolean(Settings.KEY_FIRST_RUN, true).apply()

    fun maxFreqsKhz(): List<Long> =
        (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(0L)
        }

    // Live clock of every core, for the benchmark's frequency trace. Unlike
    // cpuinfo_max_freq this changes during a run: it is what shows a pinned decode
    // holding the performance cores at their ceiling while the little cores idle.
    // Returns 0 for a core whose scaling_cur_freq the kernel will not expose.
    fun currentFreqsKhz(): List<Long> =
        (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong()
            }.getOrDefault(0L)
        }

    // Indices of the performance cores (everything above the slowest max-clock tier),
    // so a frequency trace can be split into big vs little without hardcoding 4-7.
    fun fastCoreIndices(maxFreqsKhz: List<Long>): List<Int> {
        val slowest = maxFreqsKhz.filter { it > 0L }.minOrNull() ?: return maxFreqsKhz.indices.toList()
        val fast = maxFreqsKhz.indices.filter { maxFreqsKhz[it] > slowest }
        return if (fast.isEmpty()) maxFreqsKhz.indices.toList() else fast
    }

    private fun gb(bytes: Long) = bytes / (1024.0 * 1024.0 * 1024.0)

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
