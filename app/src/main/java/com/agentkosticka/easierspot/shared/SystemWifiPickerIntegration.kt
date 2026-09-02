package com.agentkosticka.easierspot.shared

import android.content.Context
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.hotspot.WifiSuggestionInstaller
import com.agentkosticka.easierspot.util.LogUtils
import java.util.Locale

/**
 * Reconciles every path EasierSpot can use to surface a server from Android Wi-Fi Settings.
 *
 * Native Shared Connectivity is preferred because it creates true framework HotspotNetwork rows.
 * On devices where Android has selected another provider, the picker companion supplies BLE-backed
 * virtual rows even while the physical AP is off. WifiNetworkSuggestion remains the final public
 * fallback for the real AP after it becomes scan-visible; Android never displays suggestions as
 * offline rows.
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
            appendLine("Native remote entries (Shared Connectivity)")
            append(native.report())
            if (!native.capability.isActive) {
                appendLine()
                appendLine()
                append(
                    "Android Wi-Fi suggestions require a matching scan result and therefore cannot " +
                        "represent a hotspot that is still off. EasierSpot uses its Wi-Fi picker " +
                        "companion to show recently advertising paired servers before hotspot " +
                        "startup. Shizuku can enable that companion automatically; without Shizuku " +
                        "enable ‘EasierSpot Wi-Fi picker’ in Android Accessibility settings. The " +
                        "service observes Settings window changes only and does not read screen content."
                )
            }
        }
    }

    /** Runs off main: Room/WifiManager reconciliation plus optional Shizuku secure-settings setup. */
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
        SystemWifiPickerState.NATIVE_REMOTE_ENTRIES -> "native remote entries active"
        SystemWifiPickerState.SUGGESTION_ACTIVE -> "picker companion / visible-hotspot fallback active"
        SystemWifiPickerState.SUGGESTION_READY -> "visible-hotspot suggestion fallback ready"
        SystemWifiPickerState.SUGGESTION_NEEDS_REFRESH -> "offline picker companion needs setup"
        SystemWifiPickerState.SUGGESTION_APPROVAL_PENDING -> "waiting for Android suggestion approval"
        SystemWifiPickerState.SUGGESTION_APPROVAL_REJECTED -> "Android suggestion access rejected"
    }
}
