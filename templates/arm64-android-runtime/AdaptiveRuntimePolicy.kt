package com.entity.runtime

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Copyable, Android-free policy functions from ENTITY's Auto runtime.
 *
 * Gather CPU count, free memory, thermal status, and battery properties in your app layer, then
 * pass those values here. The functions stay JVM-testable and do not require Android classes.
 */
object AdaptiveRuntimePolicy {
    const val MIN_GENERATION_THREADS = 2
    const val MAX_GENERATION_THREADS = 4
    const val THREAD_HEADROOM = 2

    const val MIN_PLAUSIBLE_WATTS = 0.05
    const val MAX_PLAUSIBLE_WATTS = 15.0

    /** Mirrors ENTITY's native Auto decode width. */
    fun generationThreads(onlineCores: Int): Int =
        (onlineCores - THREAD_HEADROOM).coerceIn(MIN_GENERATION_THREADS, MAX_GENERATION_THREADS)

    /** Mirrors MainActivity.adaptiveContext(): decimal GB for model size and GiB for free RAM. */
    fun adaptiveContext(modelBytes: Long, availableBytes: Long): Int {
        val modelGb = modelBytes / 1_000_000_000.0
        val freeGiB = availableBytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            modelGb < 1.6 -> if (freeGiB > 3.0) 8192 else 4096
            else -> if (freeGiB > 2.2) 4096 else 2048
        }
    }

    /** Android thermal statuses: NONE=0, LIGHT=1, MODERATE=2, SEVERE=3 and above. */
    fun thermalDelayMs(thermalStatus: Int, efficiencyMode: Boolean): Long {
        val base = when {
            thermalStatus >= 3 -> 12L
            thermalStatus == 2 -> 6L
            else -> 0L
        }
        return if (efficiencyMode) base * 2 else base
    }

    /**
     * Resolves Android OEM kernels that report CURRENT_NOW in milliamps rather than microamps.
     * A caller should omit the value while charging because battery current is then not comparable.
     */
    fun watts(rawCurrent: Long, voltageMv: Int): Double {
        if (rawCurrent == 0L || voltageMv <= 0) return 0.0
        val magnitude = abs(rawCurrent).toDouble()
        val asMicroamps = magnitude * voltageMv / 1e9
        val asMilliamps = magnitude * 1000.0 * voltageMv / 1e9
        return when {
            plausible(asMicroamps) -> asMicroamps
            plausible(asMilliamps) -> asMilliamps
            else -> asMicroamps
        }
    }

    fun tokensPerWatt(decodeTokensPerSecond: Double, watts: Double): Double =
        if (watts > 0.0) decodeTokensPerSecond / watts else 0.0

    /** Benchmark estimate only: PP evaluation plus PL decode tokens, not live-chat TTFT. */
    fun derivedTtftMs(promptTokens: Int, promptTokensPerSecond: Double, decodeTokens: Int, decodeTokensPerSecond: Double): Double =
        if (promptTokensPerSecond > 0.0 && decodeTokensPerSecond > 0.0) {
            promptTokens * 1000.0 / promptTokensPerSecond + decodeTokens * 1000.0 / decodeTokensPerSecond
        } else 0.0

    fun median(values: List<Double>): Double {
        val sorted = values.filter { it > 0.0 }.sorted()
        if (sorted.isEmpty()) return 0.0
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    fun populationStdDev(values: List<Double>): Double {
        val valid = values.filter { it > 0.0 }
        if (valid.isEmpty()) return 0.0
        val mean = valid.average()
        return sqrt(valid.sumOf { (it - mean) * (it - mean) } / valid.size)
    }

    private fun plausible(watts: Double) = watts in MIN_PLAUSIBLE_WATTS..MAX_PLAUSIBLE_WATTS
}
