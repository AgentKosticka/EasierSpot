package com.agentkosticka.easierspot.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIdentityEvidencePolicyTest {
    private fun evidence(
        observedSsid: String? = null,
        suggestionOwned: Boolean = true,
        authenticatedAck: Boolean = false,
        authenticatedGattFallback: Boolean = false
    ) = ConnectionEvidence(
        generation = 1L,
        expectedSsid = "EasierSpot phone",
        observedSsid = observedSsid,
        suggestionOwned = suggestionOwned,
        authenticatedAck = authenticatedAck,
        authenticatedGattFallback = authenticatedGattFallback
    )

    @Test
    fun redactedSuggestedNetworkCanBeProbedButNotAcceptedFromGattAlone() {
        val candidate = evidence(authenticatedGattFallback = true)

        assertTrue(WifiIdentityEvidencePolicy.canProbeCandidate(candidate))
        assertFalse(WifiIdentityEvidencePolicy.canAcceptCandidate(candidate))
    }

    @Test
    fun authenticatedGatewayAcceptsRedactedSuggestedNetwork() {
        assertTrue(
            WifiIdentityEvidencePolicy.canAcceptCandidate(
                evidence(authenticatedAck = true)
            )
        )
    }

    @Test
    fun visibleWrongSsidIsNeverAProbeCandidate() {
        assertFalse(
            WifiIdentityEvidencePolicy.canProbeCandidate(
                evidence(observedSsid = "Home Wi-Fi")
            )
        )
    }
}
