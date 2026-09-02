package com.agentkosticka.easierspot.shared

import android.content.Context
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.util.LogUtils
import java.util.Locale

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

    private data class SuggestionSnapshot(
        val approval: WifiSuggestionInstaller.ApprovalStatus,
        val trustedNetworkCount: Int,
        val installedSuggestionCount: Int,
        val pickerSelectableSuggestionCount: Int,
        val repairedSuggestionCount: Int
    )

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
            appendLine("Picker integration mode: ${SystemWifiPickerIntegration.stateLabel(state)}")
            appendLine("Network suggestion approval: ${suggestionApproval.name.lowercase(Locale.ROOT)}")
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

    /**
     * Startup path: repair and account for app-owned suggestions only. Native Shared Connectivity
     * reconciliation is intentionally left to [SharedConnectivityBackends] so startup never repeats
     * overlay/shell activation work just to populate diagnostics.
     */
    fun reconcileSuggestions(context: Context) {
        reconcileSuggestionSnapshot(context.applicationContext)
    }

    /** Full diagnostics are computed on demand from Settings, where native framework detail matters. */
    fun diagnostics(context: Context): Diagnostics {
        val app = context.applicationContext
        val suggestions = reconcileSuggestionSnapshot(app)
        val native = SharedConnectivityActivation.diagnostics(app)
        val state = resolveSystemWifiPickerState(
            nativeRemoteEntriesActive = native.capability.isActive,
            suggestionApprovalPending = suggestions.approval == WifiSuggestionInstaller.ApprovalStatus.PENDING,
            suggestionApprovalRejected = suggestions.approval == WifiSuggestionInstaller.ApprovalStatus.REJECTED,
            trustedNetworkCount = suggestions.trustedNetworkCount,
            pickerSelectableSuggestionCount = suggestions.pickerSelectableSuggestionCount
        )
        return Diagnostics(
            state = state,
            native = native,
            suggestionApproval = suggestions.approval,
            trustedNetworkCount = suggestions.trustedNetworkCount,
            installedSuggestionCount = suggestions.installedSuggestionCount,
            pickerSelectableSuggestionCount = suggestions.pickerSelectableSuggestionCount,
            repairedSuggestionCount = suggestions.repairedSuggestionCount
        )
    }

    /** Runs off main because it reads Room, WifiManager state, and may repair Android suggestions. */
    private fun reconcileSuggestionSnapshot(context: Context): SuggestionSnapshot {
        val profiles = TrustedServerStore(context).all().distinctBy { it.ssid }
        var repaired = 0

        profiles.forEach { profile ->
            val securityType = runCatching {
                HotspotCredentials.SecurityType.valueOf(profile.securityType)
            }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
            if (WifiSuggestionInstaller.ensurePickerVisibility(
                    context,
                    profile.ssid,
                    securityType,
                    profile.isHidden
                ) == WifiSuggestionInstaller.PickerRepairResult.REPAIRED
            ) {
                repaired++
                LogUtils.diag(TAG, "Repaired Wi-Fi picker visibility for a trusted network")
            }
        }

        var installed = 0
        var pickerSelectable = 0
        profiles.forEach { profile ->
            val securityType = runCatching {
                HotspotCredentials.SecurityType.valueOf(profile.securityType)
            }.getOrDefault(HotspotCredentials.SecurityType.WPA2_PSK)
            WifiSuggestionInstaller.pickerEntryState(context, profile.ssid, securityType)?.let { entry ->
                installed++
                if (entry.manualSelectionSupported) pickerSelectable++
            }
        }

        return SuggestionSnapshot(
            approval = WifiSuggestionInstaller.approvalStatus(context),
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
