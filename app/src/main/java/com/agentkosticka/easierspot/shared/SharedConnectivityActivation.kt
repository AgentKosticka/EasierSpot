package com.agentkosticka.easierspot.shared

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.agentkosticka.easierspot.privileged.PrivilegedShellClient
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import com.agentkosticka.easierspot.util.LogUtils

object SharedConnectivityActivation {
    const val SERVICE_ACTION = "com.agentkosticka.easierspot.SHARED_CONNECTIVITY"
    private const val TAG = "SharedConnectivity"
    private const val PACKAGE_RESOURCE = "config_sharedConnectivityServicePackage"
    private const val ACTION_RESOURCE = "config_sharedConnectivityServiceIntentAction"
    private const val ENABLED_RESOURCE = "config_hotspotNetworksEnabledForService"
    private const val SERVICE_CLASS =
        "com.agentkosticka.easierspot.shared.EasierSpotSharedConnectivityService"

    @Volatile
    private var lastCapability: SharedConnectivityCapability =
        if (Build.VERSION.SDK_INT >= 34) {
            SharedConnectivityCapability.Blocked("Capability has not been reconciled yet")
        } else SharedConnectivityCapability.ApiUnavailable

    data class ResolvedConfig(
        val packageName: String?,
        val action: String?,
        val hotspotNetworksEnabled: Boolean?
    ) {
        fun selectsEasierSpot(context: Context): Boolean =
            packageName == context.packageName &&
                action == SERVICE_ACTION &&
                hotspotNetworksEnabled == true
    }

    data class Diagnostics(
        val androidApi: Int,
        val apiAvailable: Boolean,
        val configuredProviderPackage: String?,
        val configuredProviderAction: String?,
        val hotspotNetworksEnabled: Boolean?,
        val providerServiceDeclared: Boolean,
        val activationMethod: String,
        val capability: SharedConnectivityCapability
    ) {
        fun report(): String = buildString {
            appendLine("Android API: $androidApi")
            appendLine("Shared Connectivity API available: $apiAvailable")
            appendLine("Configured provider package: ${configuredProviderPackage ?: "unavailable"}")
            appendLine("Configured provider action: ${configuredProviderAction ?: "unavailable"}")
            appendLine("Hotspot network UI enabled: ${hotspotNetworksEnabled ?: "unavailable"}")
            appendLine("EasierSpot provider service declared: $providerServiceDeclared")
            appendLine("Activation method: $activationMethod")
            append("Native picker status: ${capabilityLabel(capability)}")
        }
    }

    fun capability(): SharedConnectivityCapability = lastCapability

    /** Runs off main. This intentionally returns the exact framework state used by capability gating. */
    fun diagnostics(context: Context): Diagnostics {
        val app = context.applicationContext
        val capability = reconcile(app)
        val config = if (Build.VERSION.SDK_INT >= 34) {
            directFrameworkConfig(app) ?: if (ShizukuStateMonitor.isReady()) {
                shellFrameworkConfig()
            } else null
        } else null
        val serviceDeclared = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getServiceInfo(ComponentName(app.packageName, SERVICE_CLASS), 0)
        }.isSuccess
        val activationMethod = when (capability) {
            SharedConnectivityCapability.ApiUnavailable -> "none (API unavailable)"
            SharedConnectivityCapability.ProviderAlreadyConfigured -> "framework already configured"
            SharedConnectivityCapability.ProviderActivated -> "existing shell-enableable overlay"
            is SharedConnectivityCapability.Blocked -> "none (blocked)"
        }
        return Diagnostics(
            androidApi = Build.VERSION.SDK_INT,
            apiAvailable = Build.VERSION.SDK_INT >= 34,
            configuredProviderPackage = config?.packageName,
            configuredProviderAction = config?.action,
            hotspotNetworksEnabled = config?.hotspotNetworksEnabled,
            providerServiceDeclared = serviceDeclared,
            activationMethod = activationMethod,
            capability = capability
        )
    }

    fun reconcile(context: Context): SharedConnectivityCapability {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 34) {
            return SharedConnectivityCapability.ApiUnavailable.also { lastCapability = it }
        }

        directFrameworkConfig(app)?.takeIf { it.selectsEasierSpot(app) }?.let {
            return SharedConnectivityCapability.ProviderAlreadyConfigured.also { capability ->
                lastCapability = capability
                LogUtils.diag(TAG, "Shared Connectivity provider is already configured")
            }
        }

        if (!ShizukuStateMonitor.isReady()) {
            return SharedConnectivityCapability.Blocked(
                "Android has not selected EasierSpot as its Shared Connectivity provider"
            ).also { lastCapability = it }
        }

        val shellConfig = shellFrameworkConfig()
        if (shellConfig.selectsEasierSpot(app)) {
            return SharedConnectivityCapability.ProviderAlreadyConfigured.also { capability ->
                lastCapability = capability
            }
        }

        val overlayList = PrivilegedShellClient.execute(
            arrayOf("/system/bin/cmd", "overlay", "list"),
            4_000L
        )
        val candidates = parseEasierSpotOverlayCandidates(overlayList.stdout)
        for (candidate in candidates.filterNot { it.enabled }) {
            val enabled = PrivilegedShellClient.execute(
                arrayOf("/system/bin/cmd", "overlay", "enable", candidate.packageName),
                4_000L
            )
            if (enabled.exitCode != 0) continue
            val verified = shellFrameworkConfig()
            if (verified.selectsEasierSpot(app)) {
                return SharedConnectivityCapability.ProviderActivated.also { capability ->
                    lastCapability = capability
                    LogUtils.i(TAG, "Activated Shared Connectivity through ${candidate.packageName}")
                }
            }
            // We only touch an EasierSpot-named overlay, and revert it if it did not produce the
            // exact framework state required by Settings.
            PrivilegedShellClient.execute(
                arrayOf("/system/bin/cmd", "overlay", "disable", candidate.packageName),
                4_000L
            )
        }

        return SharedConnectivityCapability.Blocked(
            buildString {
                append("Framework provider configuration is blocked")
                shellConfig.packageName?.let { append(" (provider=$it)") }
                if (shellConfig.hotspotNetworksEnabled == false) append("; hotspot network UI disabled")
            }
        ).also { lastCapability = it }
    }

    private fun directFrameworkConfig(context: Context): ResolvedConfig? {
        val resources = context.resources
        fun stringValue(name: String): String? {
            val id = resources.getIdentifier(name, "string", "android")
            return if (id == 0) null else runCatching { resources.getString(id) }.getOrNull()
        }
        fun boolValue(name: String): Boolean? {
            val id = resources.getIdentifier(name, "bool", "android")
            return if (id == 0) null else runCatching { resources.getBoolean(id) }.getOrNull()
        }
        val config = ResolvedConfig(
            packageName = stringValue(PACKAGE_RESOURCE),
            action = stringValue(ACTION_RESOURCE),
            hotspotNetworksEnabled = boolValue(ENABLED_RESOURCE)
        )
        return config.takeUnless {
            it.packageName == null && it.action == null && it.hotspotNetworksEnabled == null
        }
    }

    private fun shellFrameworkConfig(): ResolvedConfig {
        fun lookup(type: String, name: String) = PrivilegedShellClient.execute(
            arrayOf("/system/bin/cmd", "overlay", "lookup", "android", "android:$type/$name"),
            4_000L
        )
        val packageResult = lookup("string", PACKAGE_RESOURCE)
        val actionResult = lookup("string", ACTION_RESOURCE)
        val enabledResult = lookup("bool", ENABLED_RESOURCE)
        return ResolvedConfig(
            packageName = if (packageResult.exitCode == 0) parseOverlayLookupValue(packageResult.stdout) else null,
            action = if (actionResult.exitCode == 0) parseOverlayLookupValue(actionResult.stdout) else null,
            hotspotNetworksEnabled = if (enabledResult.exitCode == 0) {
                parseOverlayLookupBoolean(enabledResult.stdout)
            } else null
        )
    }

    private data class OverlayCandidate(val packageName: String, val enabled: Boolean)

    private fun parseEasierSpotOverlayCandidates(output: String): List<OverlayCandidate> =
        output.lineSequence().mapNotNull { line ->
            val match = Regex("""^\s*\[([ xX])\]\s+([A-Za-z0-9_.]+)\s*$""").find(line)
                ?: return@mapNotNull null
            val packageName = match.groupValues[2]
            if (!packageName.contains("easierspot", ignoreCase = true)) return@mapNotNull null
            OverlayCandidate(packageName, match.groupValues[1].equals("x", ignoreCase = true))
        }.toList()

    private fun capabilityLabel(capability: SharedConnectivityCapability): String = when (capability) {
        SharedConnectivityCapability.ApiUnavailable -> "Unavailable on Android 12–13"
        SharedConnectivityCapability.ProviderAlreadyConfigured -> "Active"
        SharedConnectivityCapability.ProviderActivated -> "Active (overlay activated)"
        is SharedConnectivityCapability.Blocked -> "Blocked: ${capability.reason}"
    }
}
