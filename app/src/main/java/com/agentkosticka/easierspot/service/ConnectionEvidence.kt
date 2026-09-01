package com.agentkosticka.easierspot.service

import android.net.Network
import java.net.InetAddress

/** Immutable evidence from Android callbacks and the authenticated control channel. */
data class ConnectionEvidence(
    val generation: Long,
    val expectedSsid: String,
    val network: Network? = null,
    val observedSsid: String? = null,
    val hasAssignedAddress: Boolean = false,
    val dhcpServer: InetAddress? = null,
    val suggestionOwned: Boolean = false,
    val networkOwnedByApp: Boolean = false,
    val privilegedSsidMatch: Boolean = false,
    val authenticatedAck: Boolean = false,
    val authenticatedGattFallback: Boolean = false,
    // Diagnostic-only platform metadata. This must never gate EasierSpot connection success.
    val internetValidated: Boolean = false
)

sealed interface ConnectionVerdict {
    data object WaitingForWifi : ConnectionVerdict
    data class WaitingForPhone(val network: Network, val gateway: InetAddress) : ConnectionVerdict
    data class Connected(val internet: InternetStatus = InternetStatus.NOT_CONFIRMED) : ConnectionVerdict
}

object ConnectionEvidenceReducer {
    fun reduce(evidence: ConnectionEvidence): ConnectionVerdict {
        val network = evidence.network ?: return ConnectionVerdict.WaitingForWifi
        val ownershipMatches = evidence.suggestionOwned || evidence.privilegedSsidMatch
        if (!WifiIdentityEvidencePolicy.canProbeCandidate(evidence) ||
            !ownershipMatches ||
            !evidence.hasAssignedAddress
        ) {
            return ConnectionVerdict.WaitingForWifi
        }
        if (!evidence.authenticatedAck && !evidence.authenticatedGattFallback) {
            val gateway = evidence.dhcpServer ?: return ConnectionVerdict.WaitingForWifi
            return ConnectionVerdict.WaitingForPhone(network, gateway)
        }
        // A GATT acknowledgement proves the paired phone is alive, but it does not prove that
        // this redacted Wi-Fi Network belongs to it. Only the keyed UDP response from this
        // network's gateway can safely replace explicit SSID/owner evidence.
        if (!WifiIdentityEvidencePolicy.canAcceptCandidate(evidence)) {
            return ConnectionVerdict.WaitingForWifi
        }
        // EasierSpot's job ends when Android joined the intended Wi-Fi and the paired phone was
        // authenticated. Captive portal / internet / VALIDATED state belongs to Android.
        return ConnectionVerdict.Connected()
    }
}

/** Keeps redacted Wi-Fi metadata useful without weakening connection authentication. */
object WifiIdentityEvidencePolicy {
    fun hasExplicitIdentity(evidence: ConnectionEvidence): Boolean =
        evidence.observedSsid == evidence.expectedSsid ||
            evidence.privilegedSsidMatch ||
            evidence.networkOwnedByApp

    fun canProbeCandidate(evidence: ConnectionEvidence): Boolean =
        hasExplicitIdentity(evidence) ||
            (evidence.observedSsid == null && evidence.suggestionOwned)

    fun canAcceptCandidate(evidence: ConnectionEvidence): Boolean =
        hasExplicitIdentity(evidence) ||
            (evidence.observedSsid == null &&
                evidence.suggestionOwned &&
                evidence.authenticatedAck)
}
