package com.agentkosticka.easierspot.ble.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceAlertPolicyTest {
    private fun profile(
        revision: Int = 3,
        lastPresence: Long = 1_000L,
        alerts: Boolean = true
    ) = TrustedServerProfile(
        fingerprint = "fingerprint",
        discoveryToken = "12345678",
        displayName = "Phone",
        ssid = "Network",
        advertisedRevision = revision,
        lastSeen = lastPresence,
        lastAlertRevision = revision,
        lastPresenceAt = lastPresence,
        alertsEnabled = alerts
    )

    @Test
    fun continuousPacketsAndAdvertiserRestartsDoNotRepeatPrompt() {
        assertFalse(shouldAlertForPresence(profile(), revision = 3, now = 20_000L, absenceMs = 90_000L))
    }

    @Test
    fun actualNetworkChangeOrRealReappearancePromptsOnce() {
        assertTrue(shouldAlertForPresence(profile(), revision = 4, now = 20_000L, absenceMs = 90_000L))
        assertTrue(shouldAlertForPresence(profile(), revision = 3, now = 100_000L, absenceMs = 90_000L))
    }

    @Test
    fun disabledAlertsNeverPrompt() {
        assertFalse(
            shouldAlertForPresence(profile(alerts = false), revision = 4, now = 100_000L, absenceMs = 90_000L)
        )
    }
}
