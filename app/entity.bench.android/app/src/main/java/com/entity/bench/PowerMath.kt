package com.entity.bench

import kotlin.math.abs

// Battery power from the raw BATTERY_PROPERTY_CURRENT_NOW reading. Pure (no Android
// classes) so it stays JVM-testable.
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
    fun watts(rawCurrent: Long, rawVoltage: Int): Double {
        val mv = normalizeVoltageMv(rawVoltage)
        if (rawCurrent == 0L || mv <= 0) return 0.0
        val mag = abs(rawCurrent).toDouble()
        val wattsIfMicroamps = mag * mv / 1e9
        val wattsIfMilliamps = mag * 1000.0 * mv / 1e9
        return when {
            plausible(wattsIfMicroamps) -> wattsIfMicroamps
            plausible(wattsIfMilliamps) -> wattsIfMilliamps
            else -> wattsIfMicroamps  // neither fits: fall back to the documented unit
        }
    }

    // Battery voltage normalised to millivolts.
    //
    // EXTRA_VOLTAGE is documented in millivolts and most devices comply. Some do not: an
    // OPPO CPH2737 (Dimensity 8300) reports whole VOLTS, and microvolt readings exist in
    // the wild too. However it is reported, a phone battery is 3-10 V - single cell near
    // 4 V, dual cell in series near 8 - so the magnitude identifies the unit on its own.
    // The three candidate ranges are three orders of magnitude apart and cannot overlap.
    //
    // This matters more than it looks. The microamp/milliamp heuristic above decides by
    // asking which product is a physically possible wattage. Hand it volts instead of
    // millivolts and BOTH candidates land below the plausible floor, so the heuristic
    // gives up and returns the documented unit - a reading 1000x too small on top of the
    // 1000x already lost to the current unit. That is exactly what the first Dimensity
    // 8300 results showed: 2.7 microwatts of decode and 11 million tokens per watt.
    fun normalizeVoltageMv(raw: Int): Int = when {
        raw <= 0 -> 0
        raw < 100 -> raw * 1000       // volts
        raw > 100_000 -> raw / 1000   // microvolts
        else -> raw                   // millivolts, as documented
    }

    fun plausible(watts: Double): Boolean = watts in MIN_PLAUSIBLE_W..MAX_PLAUSIBLE_W
}
