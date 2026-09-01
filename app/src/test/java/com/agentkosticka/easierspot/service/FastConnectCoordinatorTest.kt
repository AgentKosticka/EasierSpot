package com.agentkosticka.easierspot.service

import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastConnectCoordinatorTest {
    @Test
    fun unchangedProvisionedSuggestion_skipsGatt() {
        val decision = FastConnectCoordinator.decide(profile(7, 7), suggestionExists = true)
        assertFalse(decision.refreshCredentials)
    }

    @Test
    fun changedRevisionOrMissingSuggestion_requiresRefresh() {
        assertTrue(FastConnectCoordinator.decide(profile(8, 7), true).refreshCredentials)
        assertTrue(FastConnectCoordinator.decide(profile(7, 7), false).refreshCredentials)
    }

    @Test
    fun migratedProfileWithoutSecurityMetadata_requiresOneRefresh() {
        assertTrue(
            FastConnectCoordinator.decide(
                profile(7, 7).copy(securityType = "UNKNOWN"),
                suggestionExists = true
            ).refreshCredentials
        )
    }

    @Test
    fun learnedShizukuWinner_removesReadyDelay() {
        val profile = profile(7, 7).copy(shizukuLatencyMs = 1_200, suggestionLatencyMs = 3_000)
        assertEquals(0L, FastConnectCoordinator.decide(profile, true).shizukuDelayAfterReadyMs)
    }

    private fun profile(advertised: Int, provisioned: Int) = TrustedServerProfile(
        fingerprint = "abcdef0123456789",
        discoveryToken = "1234abcd",
        displayName = "Phone",
        ssid = "Spot",
        advertisedRevision = advertised,
        provisionedRevision = provisioned,
        lastSeen = 0L
    )
}
