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
    val internetValidated: Boolean = false
)

sealed interface ConnectionVerdict {
    data object WaitingForWifi : ConnectionVerdict
    data class WaitingForPhone(val network: Network, val gateway: InetAddress) : ConnectionVerdict
    data class Connected(val internet: InternetStatus) : ConnectionVerdict
}

object ConnectionEvidenceReducer {
    fun reduce(evidence: ConnectionEvidence): ConnectionVerdict {
        val network = evidence.network ?: return ConnectionVerdict.WaitingForWifi
        val ssidMatches = evidence.observedSsid == evidence.expectedSsid ||
            (evidence.observedSsid == null &&
                (evidence.privilegedSsidMatch || evidence.networkOwnedByApp))
        val ownershipMatches = evidence.suggestionOwned || evidence.privilegedSsidMatch
        if (!ssidMatches || !ownershipMatches || !evidence.hasAssignedAddress) {
            return ConnectionVerdict.WaitingForWifi
        }
        if (!evidence.authenticatedAck && !evidence.authenticatedGattFallback) {
            val gateway = evidence.dhcpServer ?: return ConnectionVerdict.WaitingForWifi
            return ConnectionVerdict.WaitingForPhone(network, gateway)
        }
        return ConnectionVerdict.Connected(
            if (evidence.internetValidated) InternetStatus.READY else InternetStatus.NOT_CONFIRMED
        )
    }
}
