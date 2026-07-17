package com.example.llama

import kotlin.math.abs

// Battery power from the raw BATTERY_PROPERTY_CURRENT_NOW reading. Pure (no Android
// classes) so it stays JVM-testable, exactly like ThermalGuard and DeviceOptimizer.
//
// Android documents CURRENT_NOW as microamps, but plenty of OEM kernels — Qualcomm ones
// especially — report milliamps instead, and nothing in the API says which you got. Taking
// the docs at their word on such a device under-reports power by 1000x, which in turn
// inflates the tokens-per-watt headline by 1000x. So we do not trust the unit: we compute
// both readings and keep the one that is physically possible for a phone.
object PowerMath {

    // A phone that is awake with the screen on draws at least this much, and even a
    // sustained-load LLM run stays under the ceiling. Anything outside the band is the
    // wrong unit, not a real measurement.
    const val MIN_PLAUSIBLE_W = 0.05
    const val MAX_PLAUSIBLE_W = 15.0

    // ponytail: plausibility heuristic, not a real unit probe. A device idling below
    // ~0.05 W would be misread as milliamps; sample under load. Upgrade path: calibrate
    // the unit once from a known-load sample.
    fun watts(rawCurrent: Long, voltageMv: Int): Double {
        if (rawCurrent == 0L || voltageMv <= 0) return 0.0
        val mag = abs(rawCurrent).toDouble()
        val wattsIfMicroamps = mag * voltageMv / 1e9
        val wattsIfMilliamps = mag * 1000.0 * voltageMv / 1e9
        return when {
            plausible(wattsIfMicroamps) -> wattsIfMicroamps
            plausible(wattsIfMilliamps) -> wattsIfMilliamps
            else -> wattsIfMicroamps  // neither fits: fall back to the documented unit
        }
    }

    fun plausible(watts: Double): Boolean = watts in MIN_PLAUSIBLE_W..MAX_PLAUSIBLE_W
}
