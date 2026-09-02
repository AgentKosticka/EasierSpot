package com.agentkosticka.easierspot.shared

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.agentkosticka.easierspot.privileged.PrivilegedShellClient
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import com.agentkosticka.easierspot.util.LogUtils
import java.lang.ref.WeakReference

internal interface WifiPickerCompanionHost {
    fun requestPickerRefresh()
}

internal object WifiPickerCompanionBridge {
    @Volatile
    private var host = WeakReference<WifiPickerCompanionHost>(null)

    fun attach(value: WifiPickerCompanionHost) {
        host = WeakReference(value)
    }

    fun detach(value: WifiPickerCompanionHost) {
        if (host.get() === value) host.clear()
    }

    fun requestRefresh() {
        host.get()?.requestPickerRefresh()
    }
}

enum class WifiPickerCompanionActivation {
    NATIVE_SUPERSEDES,
    ACTIVE,
    ENABLED_WITH_SHIZUKU,
    MANUAL_ENABLE_REQUIRED,
    SHIZUKU_ENABLE_FAILED,
    NOT_NEEDED
}

data class WifiPickerCompanionDiagnostics(
    val activation: WifiPickerCompanionActivation,
    val configured: Boolean,
    val detail: String
)

/**
 * Gives stock Settings a production fallback when this app cannot be selected as Android's hidden
 * Shared Connectivity provider. A Shizuku shell can enable our tightly-scoped accessibility
 * companion without touching any other enabled accessibility service. Without Shizuku the user can
 * enable the same service from Android Accessibility settings.
 */
object WifiPickerCompanionController {
    private const val TAG = "WifiPickerCompanion"
    private const val RETRY_WINDOW_MS = 30_000L

    @Volatile
    private var lastShizukuAttemptAt = 0L

    fun reconcile(context: Context, needed: Boolean = true): WifiPickerCompanionDiagnostics {
        val app = context.applicationContext
        val enabled = isEnabled(app)
        if (SharedConnectivityBackends.current.capability().isActive) {
            return WifiPickerCompanionDiagnostics(
                WifiPickerCompanionActivation.NATIVE_SUPERSEDES,
                enabled,
                "Native Shared Connectivity is active; the companion stays dormant to avoid duplicate rows."
            )
        }
        if (!needed) {
            return WifiPickerCompanionDiagnostics(
                WifiPickerCompanionActivation.NOT_NEEDED,
                enabled,
                "No paired EasierSpot server needs a virtual picker row yet."
            )
        }
        if (enabled) {
            return WifiPickerCompanionDiagnostics(
                WifiPickerCompanionActivation.ACTIVE,
                true,
                "Accessibility picker companion is enabled."
            )
        }
        if (!ShizukuStateMonitor.isReady()) {
            return WifiPickerCompanionDiagnostics(
                WifiPickerCompanionActivation.MANUAL_ENABLE_REQUIRED,
                false,
                "Enable EasierSpot Wi-Fi picker in Android Accessibility settings, or connect Shizuku for automatic setup."
            )
        }

        lastShizukuAttemptAt = System.currentTimeMillis()
        val component = ComponentName(app, EasierSpotWifiPickerAccessibilityService::class.java)
            .flattenToString()
        val current = readEnabledServices()
        if (current.exitCode != 0) return shizukuFailure(current.stderr.ifBlank { current.stdout })

        val merged = mergeEnabledAccessibilityServices(current.stdout, component)
        if (!enabledAccessibilitySettingContains(current.stdout, component)) {
            val put = PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/settings", "--user", "current", "put", "secure",
                    "enabled_accessibility_services", merged
                ),
                4_000L
            )
            if (put.exitCode != 0) return shizukuFailure(put.stderr.ifBlank { put.stdout })
        }

        val globalEnable = PrivilegedShellClient.execute(
            arrayOf(
                "/system/bin/settings", "--user", "current", "put", "secure",
                "accessibility_enabled", "1"
            ),
            4_000L
        )
        if (globalEnable.exitCode != 0) {
            return shizukuFailure(globalEnable.stderr.ifBlank { globalEnable.stdout })
        }

        val verified = readEnabledServices()
        if (verified.exitCode == 0 && enabledAccessibilitySettingContains(verified.stdout, component)) {
            LogUtils.i(TAG, "Enabled offline Wi-Fi picker companion through Shizuku")
            return WifiPickerCompanionDiagnostics(
                WifiPickerCompanionActivation.ENABLED_WITH_SHIZUKU,
                true,
                "Configured through Shizuku; Android will bind the picker companion automatically."
            )
        }
        return shizukuFailure("Android did not retain the accessibility service setting")
    }

    fun onPresenceChanged(context: Context) {
        WifiPickerCompanionBridge.requestRefresh()
        val app = context.applicationContext
        if (SharedConnectivityBackends.current.capability().isActive || isEnabled(app)) return
        val now = System.currentTimeMillis()
        if (!ShizukuStateMonitor.isReady() || now - lastShizukuAttemptAt < RETRY_WINDOW_MS) return
        reconcile(app, needed = true)
    }

    fun isEnabled(context: Context): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(AccessibilityManager::class.java)
        val wanted = ComponentName(app, EasierSpotWifiPickerAccessibilityService::class.java)
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo.serviceInfo
                    ComponentName(serviceInfo.packageName, serviceInfo.name) == wanted
                }
        }.getOrDefault(false)
    }

    fun manualEnableIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun readEnabledServices() = PrivilegedShellClient.execute(
        arrayOf(
            "/system/bin/settings", "--user", "current", "get", "secure",
            "enabled_accessibility_services"
        ),
        4_000L
    )

    private fun shizukuFailure(detail: String): WifiPickerCompanionDiagnostics {
        val safeDetail = detail.trim().ifBlank { "secure settings write failed" }
        LogUtils.w(TAG, "Could not enable picker companion through Shizuku: $safeDetail")
        return WifiPickerCompanionDiagnostics(
            WifiPickerCompanionActivation.SHIZUKU_ENABLE_FAILED,
            false,
            "$safeDetail. Enable EasierSpot Wi-Fi picker manually in Accessibility settings."
        )
    }
}
