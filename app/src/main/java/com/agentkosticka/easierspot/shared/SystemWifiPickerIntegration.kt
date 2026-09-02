package com.agentkosticka.easierspot.shared

import android.content.Context
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.util.LogUtils

/**
 * Reconciles both Android paths that can surface EasierSpot networks in Settings.
 *
 * Shared Connectivity supplies virtual remote hotspot entries, but Android selects that provider
 * through framework/OEM resources. A normal third-party app cannot replace a provider such as GMS.
 * WifiNetworkSuggestion is the supported public fallback: secure EasierSpot suggestions explicitly
 * share their credentials with the user so Android can surface them in the stock Wi-Fi picker once
 * the hotspot is actually visible.
 */
object SystemWifiPickerIntegration {
    private const val TAG = "SystemWifiPicker"

    data class Diagnostics(
        val state: SystemWifiPickerState,
        val native: SharedConnectivityActivation.Diagnostics,
        val suggestionApproval: WifiSuggestionInstaller.ApprovalStatus,
        val trustedNetworkCount: Int,
        val installedSuggestionCount: Int,
        val pickerSelectableSuggestionCount: Int,
        val repairedSuggestionCount: Int
    ) {
        fun report(): String = buildString {
            appendLine("Picker integration mode: ${stateLabel(state)}")
            appendLine("Network suggestion approval: ${suggestionApproval.name.lowercase()}")
            appendLine("Trusted EasierSpot networks: $trustedNetworkCount")
            appendLine("Android-owned EasierSpot suggestions: $installedSuggestionCount")
            appendLine("Picker-selectable EasierSpot suggestions: $pickerSelectableSuggestionCount")
            appendLine("Picker suggestions repaired this check: $repairedSuggestionCount")
            appendLine()
            appendLine("Native remote entries (Shared Connectivity)")
            append(native.report())
            if (!native.capability.isActive) {
                appendLine()
                appendLine()
                append(
                    "Android only allows virtual/offline hotspot entries from the framework-selected " +
                        "Shared Connectivity provider. EasierSpot therefore uses Android network " +
                        "suggestions as the stock-picker fallback; those entries become selectable " +
                        "when the hotspot is visible."
                )
            }
        }
    }

    /** Runs off main because it reads Room, WifiManager state, and may repair Android suggestions. */
    fun reconcile(context: Context): Diagnostics {
        val app = context.applicationContext
        val native = SharedConnectivityActivation.diagnostics(app)
        val profiles = TrustedServerStore(app).all().distinctBy { it.ssid }
        var repaired = 0

        profiles.forEach { profile ->
            val securityType = runCatching {
                HotspotCredentials.SecurityType.valueOf(profile.securityType)
            }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
            if (WifiSuggestionInstaller.ensurePickerVisibility(
                    app,
                    profile.ssid,
                    securityType,
                    profile.isHidden
                ) == WifiSuggestionInstaller.PickerRepairResult.REPAIRED
            ) {
                repaired++
                LogUtils.i(TAG, "Repaired Wi-Fi picker visibility for ${profile.ssid}")
            }
        }

        var installed = 0
        var pickerSelectable = 0
        profiles.forEach { profile ->
            val securityType = runCatching {
                HotspotCredentials.SecurityType.valueOf(profile.securityType)
            }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
            WifiSuggestionInstaller.pickerEntryState(app, profile.ssid, securityType)?.let { state ->
                installed++
                if (state.manualSelectionSupported) pickerSelectable++
            }
        }

        val approval = WifiSuggestionInstaller.approvalStatus(app)
        val state = resolveSystemWifiPickerState(
            nativeRemoteEntriesActive = native.capability.isActive,
            suggestionApprovalPending = approval == WifiSuggestionInstaller.ApprovalStatus.PENDING,
            suggestionApprovalRejected = approval == WifiSuggestionInstaller.ApprovalStatus.REJECTED,
            trustedNetworkCount = profiles.size,
            pickerSelectableSuggestionCount = pickerSelectable
        )
        return Diagnostics(
            state = state,
            native = native,
            suggestionApproval = approval,
            trustedNetworkCount = profiles.size,
            installedSuggestionCount = installed,
            pickerSelectableSuggestionCount = pickerSelectable,
            repairedSuggestionCount = repaired
        )
    }

    private fun stateLabel(state: SystemWifiPickerState): String = when (state) {
        SystemWifiPickerState.NATIVE_REMOTE_ENTRIES -> "native remote entries active"
        SystemWifiPickerState.SUGGESTION_ACTIVE -> "Android Wi-Fi picker fallback active"
        SystemWifiPickerState.SUGGESTION_READY -> "Android Wi-Fi picker fallback ready"
        SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH -> "saved picker entry needs credential refresh"
        SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING -> "waiting for Android suggestion approval"
        SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED -> "Android suggestion access rejected"
    }
}
