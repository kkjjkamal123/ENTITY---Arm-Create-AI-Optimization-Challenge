package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerMathTest {

    // OPPO CPH2729 (Qualcomm SM6650): the kernel reports milliamps, not the documented
    // microamps. Taken literally that is 0.0039 W, which no awake phone draws.
    @Test
    fun `milliamp kernel is read as milliamps`() {
        assertEquals(3.9, PowerMath.watts(1_000L, 3900), 0.01)
    }

    // CMF Phone 1 (MediaTek Dimensity 7300): reports true microamps.
    @Test
    fun `microamp kernel is read as microamps`() {
        assertEquals(4.68, PowerMath.watts(1_200_000L, 3900), 0.01)
    }

    @Test
    fun `discharging is reported negative on many devices`() {
        assertEquals(4.68, PowerMath.watts(-1_200_000L, 3900), 0.01)
        assertEquals(3.9, PowerMath.watts(-1_000L, 3900), 0.01)
    }

    @Test
    fun `zero and invalid readings are zero`() {
        assertEquals(0.0, PowerMath.watts(0L, 3900), 0.0)
        assertEquals(0.0, PowerMath.watts(1_200_000L, 0), 0.0)
        assertEquals(0.0, PowerMath.watts(1_200_000L, -1), 0.0)
    }

    @Test
    fun `both unit conventions land in the plausible band under load`() {
        for (voltageMv in listOf(3600, 3900, 4200)) {
            for (mA in listOf(300L, 800L, 1_500L, 2_500L)) {
                val fromMilliamps = PowerMath.watts(mA, voltageMv)
                val fromMicroamps = PowerMath.watts(mA * 1000, voltageMv)
                assertTrue("mA=$mA mV=$voltageMv", PowerMath.plausible(fromMilliamps))
                assertTrue("uA=$mA mV=$voltageMv", PowerMath.plausible(fromMicroamps))
                // Same physical current, same answer, whichever unit the kernel used.
                assertEquals(fromMicroamps, fromMilliamps, 0.001)
            }
        }
    }

    @Test
    fun `implausible readings fall back to the documented unit`() {
        // Absurdly large raw value: neither reading is a real phone, keep microamps.
        assertEquals(390.0, PowerMath.watts(100_000_000L, 3900), 0.1)
    }

    // ---- voltage unit normalisation (OPPO CPH2737 / Dimensity 8300 regression) ----

    @Test
    fun millivoltsArePassedThrough() {
        assertEquals(4000, PowerMath.normalizeVoltageMv(4000))
        assertEquals(8700, PowerMath.normalizeVoltageMv(8700))   // dual cell in series
    }

    @Test
    fun wholeVoltsAreScaledUp() {
        assertEquals(4000, PowerMath.normalizeVoltageMv(4))
        assertEquals(9000, PowerMath.normalizeVoltageMv(9))
    }

    @Test
    fun microvoltsAreScaledDown() {
        assertEquals(4000, PowerMath.normalizeVoltageMv(4_000_000))
    }

    @Test
    fun nonPositiveVoltageIsZero() {
        assertEquals(0, PowerMath.normalizeVoltageMv(0))
        assertEquals(0, PowerMath.normalizeVoltageMv(-1))
    }

    // The bug itself: milliamp current AND volt-scale voltage together put both
    // candidate wattages below the plausible floor, so the heuristic fell through to
    // the documented unit and under-reported by 1e6.
    @Test
    fun voltScaleVoltageStillYieldsPlausibleWatts() {
        val w = PowerMath.watts(679L, 4)          // 679 mA reported, 4 V reported
        assertTrue("expected a plausible wattage, got $w", PowerMath.plausible(w))
        assertEquals(2.716, w, 0.01)
    }

    @Test
    fun voltAndMillivoltFormsAgree() {
        assertEquals(PowerMath.watts(679L, 4000), PowerMath.watts(679L, 4), 1e-9)
    }

    @Test
    fun microampDeviceUnaffectedByTheFix() {
        // 1.5 A reported honestly in microamps at 4.0 V -> 6 W, still the microamp branch.
        assertEquals(6.0, PowerMath.watts(1_500_000L, 4000), 0.01)
    }

}
