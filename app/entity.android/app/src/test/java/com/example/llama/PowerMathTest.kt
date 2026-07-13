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
}
