package com.agentkosticka.easierspot.data.model

data class HotspotCredentials(
    val ssid: String,
    val password: String,
    val securityType: SecurityType = if (password.isEmpty()) SecurityType.OPEN else SecurityType.WPA2_PSK,
    val isHidden: Boolean = false
) {
    enum class SecurityType {
        OPEN,
        WPA2_PSK,
        WPA3_SAE,
        WPA3_TRANSITION
    }
}
