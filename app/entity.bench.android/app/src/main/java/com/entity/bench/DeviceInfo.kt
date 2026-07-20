package com.entity.bench

import java.io.File

// CPU topology readers. Pure functions (no Android classes) so they stay JVM-testable.
object DeviceInfo {

    // Mirrors the native clamp in ai_chat.cpp (N_THREADS_MIN / N_THREADS_MAX).
    const val MIN_THREADS = 2
    const val MAX_THREADS = 6

    // Generation thread count derived from CPU topology: the cores in the top frequency
    // cluster (cpuinfo_max_freq within ~10% of the fastest core), clamped to
    // [MIN_THREADS, MAX_THREADS]. Mirrors top_cluster_core_count() in ai_chat.cpp. On a
    // 4+4 big.LITTLE phone the little cores fall below the 10% threshold, so exactly the
    // four performance cores count. Falls back to half the cores when cpufreq is unreadable.
    // Thread widths worth sweeping on this device: 2/4/6/8 capped at the core count,
    // plus whatever Auto derives, so the shipped policy always appears as a row in the
    // table it is being judged against. One definition, so the pre-run estimate on the
    // home screen and the run itself cannot drift apart.
    fun sweepThreadCounts(maxFreqsKhz: List<Long>): List<Int> {
        val ncpu = maxFreqsKhz.size.coerceAtLeast(2)
        return (listOf(2, 4, 6, 8) + topClusterCoreCount(maxFreqsKhz))
            .filter { it in 2..ncpu }
            .distinct()
            .sorted()
    }

    fun topClusterCoreCount(maxFreqsKhz: List<Long>): Int {
        val top = maxFreqsKhz.maxOrNull() ?: 0L
        if (top <= 0L) return (maxFreqsKhz.size / 2).coerceAtLeast(1).coerceIn(MIN_THREADS, MAX_THREADS)
        val threshold = top - top / 10
        return maxFreqsKhz.count { it >= threshold }.coerceIn(MIN_THREADS, MAX_THREADS)
    }

    // How many cores are NOT in the slowest frequency tier. Same idea as
    // ranked_fast_cpus() in ai_chat.cpp: on a tri-cluster chip the mid cores count as
    // fast too. Drives affinity and the telemetry split, not the thread count.
    fun fastCoreCount(maxFreqsKhz: List<Long>): Int {
        val top = maxFreqsKhz.maxOrNull() ?: 0L
        if (top <= 0L) return (maxFreqsKhz.size / 2).coerceAtLeast(1)
        val slowest = maxFreqsKhz.min()
        val fast = maxFreqsKhz.count { it > slowest }
        return if (fast == 0) maxFreqsKhz.size else fast
    }

    // Indices of the performance cores (everything above the slowest max-clock tier),
    // so a frequency trace can be split into big vs little without hardcoding 4-7.
    fun fastCoreIndices(maxFreqsKhz: List<Long>): List<Int> {
        val slowest = maxFreqsKhz.filter { it > 0L }.minOrNull() ?: return maxFreqsKhz.indices.toList()
        val fast = maxFreqsKhz.indices.filter { maxFreqsKhz[it] > slowest }
        return if (fast.isEmpty()) maxFreqsKhz.indices.toList() else fast
    }

    // Pull the human-readable ISA features out of llama's system-info string, which
    // reports the backend variant ggml actually dlopen'd for this CPU.
    fun cpuFeatures(systemInfo: String): List<String> {
        val on = systemInfo.split('|').mapNotNull {
            val kv = it.split('=')
            if (kv.size == 2 && kv[1].trim() == "1") kv[0].trim().substringAfterLast(' ').uppercase()
            else null
        }.toSet()
        // Token names as ggml's CPU backend reports them (MATMUL_INT8 is i8mm).
        val friendly = linkedMapOf(
            "SME" to "SME",
            "SVE" to "SVE",
            "MATMUL_INT8" to "i8mm",
            "DOTPROD" to "dotprod",
        )
        return friendly.filterKeys { it in on }.values.distinct()
    }

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
}
