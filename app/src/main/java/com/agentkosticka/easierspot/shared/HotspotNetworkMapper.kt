package com.agentkosticka.easierspot.shared

import android.net.wifi.WifiInfo
import android.net.wifi.sharedconnectivity.app.HotspotNetwork
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo
import android.os.Bundle
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.data.model.HotspotCredentials

object HotspotNetworkMapper {
    const val EXTRA_FINGERPRINT = "com.agentkosticka.easierspot.extra.FINGERPRINT"
    const val EXTRA_DISCOVERY_TOKEN = "com.agentkosticka.easierspot.extra.DISCOVERY_TOKEN"
    const val EXTRA_PROTOCOL = "com.agentkosticka.easierspot.extra.PROTOCOL"

    fun map(profile: TrustedServerProfile, forceHotspotActive: Boolean = false): HotspotNetwork {
        val provider = NetworkProviderInfo.Builder(
            profile.label,
            profile.displayName.ifBlank { profile.label }
        )
            .setDeviceType(NetworkProviderInfo.DEVICE_TYPE_PHONE)
            .build()
        val extras = Bundle().apply {
            putString(EXTRA_FINGERPRINT, profile.fingerprint)
            putString(EXTRA_DISCOVERY_TOKEN, profile.discoveryToken)
            putInt(EXTRA_PROTOCOL, 3)
        }
        val builder = HotspotNetwork.Builder()
            .setDeviceId(stableSharedConnectivityDeviceId(profile.fingerprint))
            .setNetworkProviderInfo(provider)
            .setHostNetworkType(HotspotNetwork.NETWORK_TYPE_UNKNOWN)
            .setNetworkName("EasierSpot")
            .setExtras(extras)

        val hotspotActive = forceHotspotActive ||
            profile.lastPresenceFlags and BleConstants.FLAG_HOTSPOT_ACTIVE != 0
        if (hotspotActive && profile.ssid.isNotBlank()) {
            builder.setHotspotSsid(profile.ssid)
            sharedSecurityTypes(profile.securityType).forEach { type ->
                builder.addHotspotSecurityType(type)
            }
        }
        return builder.build()
    }

    fun fingerprint(network: HotspotNetwork): String? =
        network.extras?.getString(EXTRA_FINGERPRINT)

    private fun sharedSecurityTypes(raw: String): List<Int> = when (
        runCatching { HotspotCredentials.SecurityType.valueOf(raw) }
            .getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
    ) {
        HotspotCredentials.SecurityType.OPEN -> listOf(WifiInfo.SECURITY_TYPE_OPEN)
        HotspotCredentials.SecurityType.WPA2_PSK -> listOf(WifiInfo.SECURITY_TYPE_PSK)
        HotspotCredentials.SecurityType.WPA3_SAE -> listOf(WifiInfo.SECURITY_TYPE_SAE)
        HotspotCredentials.SecurityType.WPA3_TRANSITION ->
            listOf(WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_SAE)
    }
}
