package com.agentkosticka.easierspot.shared

import android.content.Context
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.util.LogUtils
import java.util.Locale

/**
 * Reconciles every path EasierSpot can use to surface a nearby server from Android Wi-Fi Settings.
 *
 * Shared Connectivity is preferred because it creates a real framework HotspotNetwork entry while
 * the server's physical Wi-Fi AP is still off. EasierSpot can use an already-selected provider, a
 * pre-installed mutable overlay, or verified fabricated framework overlays when Shizuku is running
 * as root. Regular shell-backed Shizuku cannot fabricate framework overlays.
 *
 * Where native provider selection is unavailable, the tightly scoped accessibility picker companion
 * is the virtual-row fallback. Shizuku can enable it without replacing other accessibility services;
 * without Shizuku the user can opt in from Android Accessibility settings. WifiNetworkSuggestion is
 * retained as the final public fallback for the real AP after its SSID becomes scan-visible.
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
        val companion: WifiPickerCompanionDiagnostics,
        val suggestionApproval: WifiSuggestionInstaller.ApprovalStatus,
        val trustedNetworkCount: Int,
        val installedSuggestionCount: Int,
        val pickerSelectableSuggestionCount: Int,
        val repairedSuggestionCount: Int
    ) {
        fun report(): String = buildString {
            appendLine("Picker integration mode: ${SystemWifiPickerIntegration.stateLabel(state)}")
            appendLine("Nearby/offline picker companion: ${companion.activation.name.lowercase(Locale.ROOT)}")
            appendLine("Picker companion configured: ${companion.configured}")
            appendLine("Picker companion detail: ${companion.detail}")
            appendLine("Network suggestion approval: ${suggestionApproval.name.lowercase(Locale.ROOT)}")
            appendLine("Trusted EasierSpot networks: $trustedNetworkCount")
            appendLine("Android-owned EasierSpot suggestions: $installedSuggestionCount")
            appendLine("Visible-AP picker suggestions: $pickerSelectableSuggestionCount")
            appendLine("Picker suggestions repaired this check: $repairedSuggestionCount")
            appendLine()
            appendLine("Native offline entries (Shared Connectivity)")
            append(native.report())
            if (!native.capability.isActive) {
                appendLine()
                appendLine()
                append(
                    "Android Wi-Fi suggestions require a matching scan result, so they cannot " +
                        "represent a hotspot that is still off. EasierSpot therefore uses the " +
                        "Wi-Fi picker companion for recently advertising paired servers when " +
                        "native Shared Connectivity is unavailable. The companion only observes " +
                        "Settings window-state changes and does not retrieve window content."
                )
            }
        }
    }

    /** Runs off main: Room/WifiManager reconciliation plus optional Shizuku companion setup. */
    fun reconcileSuggestions(context: Context) {
        val app = context.applicationContext
        val suggestions = reconcileSuggestionSnapshot(app)
        WifiPickerCompanionController.reconcile(
            app,
            needed = suggestions.trustedNetworkCount > 0
        )
    }

    /** Full diagnostics are computed on demand from Settings, where framework detail matters. */
    fun diagnostics(context: Context): Diagnostics {
        val app = context.applicationContext
        val suggestions = reconcileSuggestionSnapshot(app)
        val native = SharedConnectivityActivation.diagnostics(app)
        val companion = WifiPickerCompanionController.reconcile(
            app,
            needed = suggestions.trustedNetworkCount > 0
        )
        val state = resolveSystemWifiPickerState(
            nativeRemoteEntriesActive = native.capability.isActive,
            suggestionApprovalPending = suggestions.approval == WifiSuggestionInstaller.ApprovalStatus.PENDING,
            suggestionApprovalRejected = suggestions.approval == WifiSuggestionInstaller.ApprovalStatus.REJECTED,
            trustedNetworkCount = suggestions.trustedNetworkCount,
            pickerSelectableSuggestionCount = suggestions.pickerSelectableSuggestionCount,
            companionConfigured = companion.configured
        )
        return Diagnostics(
            state = state,
            native = native,
            companion = companion,
            suggestionApproval = suggestions.approval,
            trustedNetworkCount = suggestions.trustedNetworkCount,
            installedSuggestionCount = suggestions.installedSuggestionCount,
            pickerSelectableSuggestionCount = suggestions.pickerSelectableSuggestionCount,
            repairedSuggestionCount = suggestions.repairedSuggestionCount
        )
    }

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
        SystemWifiPickerState.NATIVE_REMOTE_ENTRIES -> "native offline remote entries active"
        SystemWifiPickerState.SUGGESTION_ACTIVE -> "offline picker companion / visible-hotspot fallback active"
        SystemWifiPickerState.SUGGESTION_READY -> "visible-hotspot suggestion fallback ready"
        SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH -> "offline picker companion needs setup"
        SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING -> "waiting for Android suggestion approval"
        SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED -> "Android suggestion access rejected"
    }
}
