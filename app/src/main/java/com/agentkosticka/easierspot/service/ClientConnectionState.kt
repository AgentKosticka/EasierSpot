package com.agentkosticka.easierspot.service

import com.agentkosticka.easierspot.ui.settings.AppPreferences

sealed interface ClientConnectionState {
    data object Idle : ClientConnectionState
    data class Locating(val label: String) : ClientConnectionState
    data class BleConnecting(val label: String, val attempt: Int, val maxAttempts: Int = 3) : ClientConnectionState
    data class Authenticating(val label: String) : ClientConnectionState
    data class AwaitingApproval(val label: String, val pairingCode: String?) : ClientConnectionState
    data class StartingHotspot(val label: String, val takingLonger: Boolean = false) : ClientConnectionState
    data class ReceivingCredentials(val label: String) : ClientConnectionState
    data class JoiningWifi(
        val ssid: String,
        val method: WifiJoinMethod,
        val takingLonger: Boolean = false,
        val fallbackActive: Boolean = false
    ) : ClientConnectionState
    data class Connected(
        val ssid: String,
        val internet: InternetStatus = InternetStatus.NOT_CONFIRMED,
        val controlAvailable: Boolean = true
    ) : ClientConnectionState
    data class Recovering(val label: String, val attempt: Int, val maxAttempts: Int = 3) : ClientConnectionState
    data class Failed(
        val title: String,
        val detail: String,
        val recovery: ClientRecoveryAction = ClientRecoveryAction.RETRY
    ) : ClientConnectionState
}

enum class WifiJoinMethod { SHIZUKU, SUGGESTION }
enum class InternetStatus { READY, NOT_CONFIRMED }
enum class ClientRecoveryAction { RETRY, WIFI_SETTINGS, SHIZUKU, PERMISSIONS }

object ClientConnectionPolicy {
    const val BLE_REACQUIRE_MS = 8_000L
    const val GATT_ATTEMPT_MS = 8_000L
    const val GATT_MAX_ATTEMPTS = 2
    const val APPROVAL_MS = 120_000L
    const val HOTSPOT_SOFT_MS = 10_000L
    const val HOTSPOT_TOTAL_MS = 25_000L
    const val WIFI_POLL_MS = 1_000L
    const val WIFI_NUDGE_CHECK = 5
    const val WIFI_FALLBACK_CHECK = 10
    const val WIFI_CHECKS = 20
    const val WHOLE_ATTEMPT_MS = 45_000L

    /**
     * A shell return code only describes the command invocation, not the asynchronous Wi-Fi
     * transition. Some OEM WifiService implementations return a rejection after already
     * scheduling the connection. Forced Shizuku mode must therefore keep observing evidence;
     * Auto mode may additionally start its suggestion fallback.
     */
    fun shouldStartSuggestionFallback(
        mode: AppPreferences.WifiConnectionMode,
        commandAccepted: Boolean
    ): Boolean = !commandAccepted && mode == AppPreferences.WifiConnectionMode.AUTO
}

internal fun ClientConnectionState.titleAndText(): Pair<String, String> = when (this) {
    ClientConnectionState.Idle -> "EasierSpot" to "Ready"
    is ClientConnectionState.Locating -> "Finding $label" to "Refreshing the nearby Bluetooth address…"
    is ClientConnectionState.BleConnecting -> "Connecting to $label" to "Bluetooth attempt $attempt of $maxAttempts"
    is ClientConnectionState.Authenticating -> "Authenticating $label" to "Verifying the paired phone…"
    is ClientConnectionState.AwaitingApproval -> "Approval needed on $label" to
        (pairingCode?.let { "Compare pairing code $it on both devices" } ?: "Approve this device on the sharing phone")
    is ClientConnectionState.StartingHotspot -> "Starting $label" to if (takingLonger) {
        "The phone is still starting its hotspot. This can take longer on some devices."
    } else "Waking the phone and starting its hotspot…"
    is ClientConnectionState.ReceivingCredentials -> "${label} is ready" to "Receiving the secure Wi-Fi details…"
    is ClientConnectionState.JoiningWifi -> "Connecting to $ssid" to when {
        fallbackActive -> "The first method stalled; EasierSpot is trying Android's fallback automatically…"
        takingLonger -> "Android is still switching Wi-Fi. EasierSpot will keep watching for success…"
        method == WifiJoinMethod.SHIZUKU -> "Shizuku accepted the switch; waiting for Android to confirm it…"
        else -> "Waiting for Android to select the EasierSpot network suggestion…"
    }
    is ClientConnectionState.Connected -> "Connected via EasierSpot" to when (internet) {
        InternetStatus.READY -> "$ssid · Internet ready"
        InternetStatus.NOT_CONFIRMED -> "$ssid · Internet not confirmed"
    }
    is ClientConnectionState.Recovering -> "Restoring $label" to "Bluetooth control attempt $attempt of $maxAttempts"
    is ClientConnectionState.Failed -> title to detail
}
