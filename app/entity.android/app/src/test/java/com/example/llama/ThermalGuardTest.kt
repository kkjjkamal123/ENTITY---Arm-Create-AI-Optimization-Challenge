package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardTest {

    @Test
    fun `no delay below moderate`() {
        assertEquals(0L, ThermalGuard.delayMs(0, false))
        assertEquals(0L, ThermalGuard.delayMs(1, false))
    }

    @Test
    fun `moderate throttles lightly`() {
        assertEquals(6L, ThermalGuard.delayMs(2, false))
    }

    @Test
    fun `severe and above throttle hard`() {
        for (status in 3..6) {
            assertEquals("status $status", 12L, ThermalGuard.delayMs(status, false))
        }
    }

    @Test
    fun `efficiency mode doubles the delay`() {
        assertEquals(0L, ThermalGuard.delayMs(0, true))
        assertEquals(0L, ThermalGuard.delayMs(1, true))
        assertEquals(12L, ThermalGuard.delayMs(2, true))
        assertEquals(24L, ThermalGuard.delayMs(3, true))
    }

    @Test
    fun `delay never decreases as status rises`() {
        for (efficiency in listOf(false, true)) {
            for (status in 1..6) {
                val prev = ThermalGuard.delayMs(status - 1, efficiency)
                val curr = ThermalGuard.delayMs(status, efficiency)
                assertTrue("status $status efficiency $efficiency", curr >= prev)
            }
        }
    }
}
