package com.agentkosticka.easierspot.hotspot

import android.content.Context
import com.agentkosticka.easierspot.ble.BleConstants
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared system view of hotspot consumers.
 *
 * The platform tethered-client callback is best-effort. When it cannot be registered (or has not
 * delivered its initial snapshot yet), [lifecycleLeaseIds] deliberately returns an opaque sentinel
 * so EasierSpot cannot auto-stop a hotspot that may still have ordinary Wi-Fi clients attached.
 */
object HotspotClientRegistry {
    data class ExternalClientSummary(
        val stableId: String,
        val label: String,
        val detail: String
    )

    private data class AuthenticatedAddress(
        val address: String,
        val lastSeenAt: Long
    )

    private const val EXTERNAL_LEASE_PREFIX = "external-wifi-"
    private const val UNKNOWN_LEASE = "external-state-unknown"

    private val authenticatedAddresses = ConcurrentHashMap<String, AuthenticatedAddress>()
    private val _externalClients = MutableStateFlow<List<ExternalClientSummary>>(emptyList())
    val externalClients: StateFlow<List<ExternalClientSummary>> = _externalClients.asStateFlow()
    private val _detectionKnown = MutableStateFlow(false)
    val detectionKnown: StateFlow<Boolean> = _detectionKnown.asStateFlow()

    @Volatile private var tetheredClients: List<TetheredWifiClient> = emptyList()
    @Volatile private var monitor: TetheredClientMonitor? = null

    @Synchronized
    fun initialize(context: Context) {
        if (monitor != null) return
        val created = TetheredClientMonitor(context) { clients ->
            tetheredClients = clients
            _detectionKnown.value = true
            publishExternalClients()
        }
        monitor = created
        if (!created.start()) {
            _detectionKnown.value = false
            publishExternalClients()
        }
    }

    fun markEasierSpotClient(fingerprint: String, sourceAddress: String?) {
        val address = sourceAddress?.takeIf(String::isNotBlank) ?: return
        authenticatedAddresses[fingerprint] = AuthenticatedAddress(address, System.currentTimeMillis())
        publishExternalClients()
    }

    fun forgetEasierSpotClient(fingerprint: String) {
        authenticatedAddresses.remove(fingerprint)
        publishExternalClients()
    }

    /** Opaque IDs consumed only as server lifecycle leases; they contain no MAC/IP identifiers. */
    fun lifecycleLeaseIds(now: Long = System.currentTimeMillis()): Set<String> {
        purgeExpiredAuthenticatedAddresses(now)
        if (!_detectionKnown.value) return setOf(UNKNOWN_LEASE)
        return currentExternalClients().mapTo(linkedSetOf()) { client ->
            EXTERNAL_LEASE_PREFIX + opaqueId(client.stableId)
        }
    }

    fun isExternalLifecycleLease(stableId: String): Boolean =
        stableId == UNKNOWN_LEASE || stableId.startsWith(EXTERNAL_LEASE_PREFIX)

    private fun publishExternalClients() {
        purgeExpiredAuthenticatedAddresses(System.currentTimeMillis())
        _externalClients.value = if (_detectionKnown.value) {
            currentExternalClients().map { client ->
                val label = client.hostname?.takeIf(String::isNotBlank) ?: "Wi-Fi device"
                val detail = client.addresses.sorted().joinToString(" · ")
                    .ifBlank { "Connected through hotspot" }
                ExternalClientSummary(
                    stableId = EXTERNAL_LEASE_PREFIX + opaqueId(client.stableId),
                    label = label,
                    detail = detail
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        } else emptyList()
    }

    private fun currentExternalClients(): List<TetheredWifiClient> {
        val easierspotAddresses = authenticatedAddresses.values.mapTo(hashSetOf()) { it.address }
        return tetheredClients.filter { client ->
            client.addresses.none(easierspotAddresses::contains)
        }
    }

    private fun purgeExpiredAuthenticatedAddresses(now: Long) {
        val expiry = BleConstants.UDP_CLIENT_EXPIRY_MS + 10_000L
        authenticatedAddresses.entries.removeIf { now - it.value.lastSeenAt >= expiry }
    }

    private fun opaqueId(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
