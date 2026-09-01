package com.agentkosticka.easierspot.ble.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeBoostLimiterTest {
    @Test
    fun enforcesPerPeerAndGlobalCooldowns() {
        val limiter = WakeBoostLimiter()
        assertTrue(limiter.allow("client-a", 100_000L))
        assertFalse(limiter.allow("client-a", 120_000L))
        assertFalse(limiter.allow("client-b", 110_000L))
        assertTrue(limiter.allow("client-b", 131_000L))
    }

    @Test
    fun capsTotalBoostsWithinWindow() {
        val limiter = WakeBoostLimiter()
        repeat(WakeBoostLimiter.MAX_GLOBAL_BOOSTS_PER_WINDOW) { index ->
            assertTrue(limiter.allow("client-$index", index * 31_000L))
        }
        assertFalse(limiter.allow("overflow", 200_000L))
        assertTrue(limiter.allow("after-window", WakeBoostLimiter.GLOBAL_WINDOW_MS + 200_000L))
    }
}
