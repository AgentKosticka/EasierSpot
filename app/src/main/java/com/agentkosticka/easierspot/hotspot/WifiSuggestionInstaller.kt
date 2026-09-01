package com.agentkosticka.easierspot.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.util.LogUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Installs the persistent app-owned network entry used by both foreground and headless flows. */
object WifiSuggestionInstaller {
    private const val TAG = "WifiSuggestionInstaller"

    enum class ApprovalStatus { APPROVED, PENDING, REJECTED, UNKNOWN }

    sealed interface InstallResult {
        data object Installed : InstallResult
        data object AlreadyInstalled : InstallResult
        data object AppDisallowed : InstallResult
        data object LimitReached : InstallResult
        data object Invalid : InstallResult
        data class Failed(val status: Int?, val detail: String) : InstallResult

        val accepted: Boolean
            get() = this is Installed || this is AlreadyInstalled
    }

    fun install(
        context: Context,
        credentials: HotspotCredentials,
        autojoinEnabled: Boolean = true
    ): Boolean = installDetailed(context, credentials, autojoinEnabled).accepted

    fun installDetailed(
        context: Context,
        credentials: HotspotCredentials,
        autojoinEnabled: Boolean = true
    ): InstallResult {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val suggestion = build(credentials, autojoinEnabled)
        // Adding the same network is Android's in-place update path. Removing first can disconnect
        // an active suggestion and also destroys the last known-good record if the replacement is
        // rejected.
        val status = runCatching { wifiManager.addNetworkSuggestions(listOf(suggestion)) }
            .getOrElse {
                LogUtils.w(TAG, "Could not install the EasierSpot network suggestion", it)
                return InstallResult.Failed(null, it.message ?: it.javaClass.simpleName)
            }
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> InstallResult.Installed
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> InstallResult.AlreadyInstalled
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> InstallResult.AppDisallowed
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> InstallResult.LimitReached
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID -> InstallResult.Invalid
            else -> InstallResult.Failed(status, "Android rejected the Wi-Fi suggestion")
        }
    }

    fun approvalStatus(context: Context): ApprovalStatus {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val observed = AtomicInteger(WifiManager.STATUS_SUGGESTION_APPROVAL_UNKNOWN)
        val latch = CountDownLatch(1)
        val listener = WifiManager.SuggestionUserApprovalStatusListener { status ->
            observed.set(status)
            latch.countDown()
        }
        return runCatching {
            wifiManager.addSuggestionUserApprovalStatusListener(Runnable::run, listener)
            latch.await(750L, TimeUnit.MILLISECONDS)
            wifiManager.removeSuggestionUserApprovalStatusListener(listener)
            when (observed.get()) {
                WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER,
                WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_CARRIER_PRIVILEGE -> ApprovalStatus.APPROVED
                WifiManager.STATUS_SUGGESTION_APPROVAL_PENDING -> ApprovalStatus.PENDING
                WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER -> ApprovalStatus.REJECTED
                else -> ApprovalStatus.UNKNOWN
            }
        }.getOrDefault(ApprovalStatus.UNKNOWN)
    }

    fun removeForSsid(context: Context, ssid: String): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val owned = runCatching { wifiManager.networkSuggestions }
            .getOrDefault(emptyList())
            .filter { it.ssid == ssid }
        if (owned.isEmpty()) return true
        return runCatching {
            wifiManager.removeNetworkSuggestions(owned) ==
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        }.getOrDefault(false)
    }

    fun contains(context: Context, ssid: String): Boolean =
        ownedSuggestion(context, ssid) != null

    fun ensureAutojoin(
        context: Context,
        credentials: HotspotCredentials,
        enabled: Boolean
    ): Boolean {
        val current = ownedSuggestion(context, credentials.ssid)
        if (current?.isInitialAutojoinEnabled == enabled) return true
        return install(context, credentials, enabled)
    }

    /** Reads the credential only from Android's copy of this app's suggestion. */
    fun runtimeCredentials(
        context: Context,
        ssid: String,
        securityType: HotspotCredentials.SecurityType,
        isHidden: Boolean
    ): HotspotCredentials? {
        val suggestion = ownedSuggestion(context, ssid) ?: return null
        val passphrase = suggestion.passphrase.orEmpty()
        if (securityType != HotspotCredentials.SecurityType.OPEN && passphrase.isBlank()) return null
        return HotspotCredentials(
            ssid = suggestion.ssid ?: ssid,
            password = passphrase,
            securityType = securityType,
            isHidden = isHidden || suggestion.isHiddenSsid
        )
    }

    private fun ownedSuggestion(context: Context, ssid: String): WifiNetworkSuggestion? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return runCatching { wifiManager.networkSuggestions }
            .getOrDefault(emptyList())
            .firstOrNull { it.ssid == ssid }
    }

    fun build(
        credentials: HotspotCredentials,
        autojoinEnabled: Boolean = true
    ): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(credentials.ssid)
            .setIsHiddenSsid(credentials.isHidden)
            .setIsInitialAutojoinEnabled(autojoinEnabled)
            .apply {
                when (credentials.securityType) {
                    HotspotCredentials.SecurityType.OPEN -> Unit
                    HotspotCredentials.SecurityType.WPA2_PSK,
                    HotspotCredentials.SecurityType.WPA3_TRANSITION ->
                        setWpa2Passphrase(credentials.password)
                    HotspotCredentials.SecurityType.WPA3_SAE ->
                        setWpa3Passphrase(credentials.password)
                }
            }
            .build()
}
