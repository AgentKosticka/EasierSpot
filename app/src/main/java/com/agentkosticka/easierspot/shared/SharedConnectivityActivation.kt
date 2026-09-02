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
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val DEVICE_CONFIG_NAMESPACE = "wifi"
    private const val DEVICE_CONFIG_KEY = "shared_connectivity_enabled"
    private const val FABRICATED_OVERLAY_PACKAGE = "com.android.shell"
    private const val OVERLAY_USER = "current"

    @Volatile
    private var lastCapability: SharedConnectivityCapability =
        if (Build.VERSION.SDK_INT >= 34) {
            SharedConnectivityCapability.Blocked("Capability has not been reconciled yet")
        } else SharedConnectivityCapability.ApiUnavailable

    @Volatile private var lastActivationMethod = "not reconciled"
    @Volatile private var lastPrivilegedUid: Int? = null

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
        val settingsFeatureEnabled: Boolean?,
        val providerServiceDeclared: Boolean,
        val privilegedUid: Int?,
        val activationMethod: String,
        val capability: SharedConnectivityCapability
    ) {
        fun report(): String = buildString {
            appendLine("Android API: $androidApi")
            appendLine("Shared Connectivity API available: $apiAvailable")
            appendLine("Configured provider package: ${configuredProviderPackage ?: "unavailable"}")
            appendLine("Configured provider action: ${configuredProviderAction ?: "unavailable"}")
            appendLine("Hotspot network UI enabled: ${hotspotNetworksEnabled ?: "unavailable"}")
            appendLine("Settings Shared Connectivity feature enabled: ${settingsFeatureEnabled ?: "unavailable"}")
            appendLine("EasierSpot provider service declared: $providerServiceDeclared")
            appendLine("Shizuku privileged UID: ${privilegedUid ?: "unavailable"}")
            appendLine("Activation method: $activationMethod")
            append("Native picker status: ${capabilityLabel(capability)}")
        }
    }

    private data class FeatureGateResult(
        val enabled: Boolean,
        val changed: Boolean,
        val detail: String? = null
    )

    private data class FabricationResult(
        val success: Boolean,
        val detail: String? = null
    )

    fun capability(): SharedConnectivityCapability = lastCapability

    /** Runs off main. This intentionally returns the exact framework state used by capability gating. */
    fun diagnostics(context: Context): Diagnostics {
        val app = context.applicationContext
        val capability = reconcile(app)
        val shizukuReady = ShizukuStateMonitor.isReady()
        val config = if (Build.VERSION.SDK_INT >= 34) {
            if (shizukuReady) shellFrameworkConfig() else directFrameworkConfig(app)
        } else null
        val serviceDeclared = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getServiceInfo(ComponentName(app.packageName, SERVICE_CLASS), 0)
        }.isSuccess
        return Diagnostics(
            androidApi = Build.VERSION.SDK_INT,
            apiAvailable = Build.VERSION.SDK_INT >= 34,
            configuredProviderPackage = config?.packageName,
            configuredProviderAction = config?.action,
            hotspotNetworksEnabled = config?.hotspotNetworksEnabled,
            settingsFeatureEnabled = if (shizukuReady) readSettingsFeatureGate() else null,
            providerServiceDeclared = serviceDeclared,
            privilegedUid = lastPrivilegedUid,
            activationMethod = lastActivationMethod,
            capability = capability
        )
    }

    fun reconcile(context: Context): SharedConnectivityCapability {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < 34) {
            return remember(
                SharedConnectivityCapability.ApiUnavailable,
                "none (Shared Connectivity requires Android 14+)"
            )
        }

        val directConfig = directFrameworkConfig(app)
        if (directConfig?.selectsEasierSpot(app) == true && !ShizukuStateMonitor.isReady()) {
            return remember(
                SharedConnectivityCapability.ProviderAlreadyConfigured,
                "framework already configured"
            ).also {
                LogUtils.diag(TAG, "Shared Connectivity provider is already configured")
            }
        }

        if (!ShizukuStateMonitor.isReady()) {
            return remember(
                SharedConnectivityCapability.Blocked(
                    "Android has not selected EasierSpot as its Shared Connectivity provider"
                ),
                "none (Shizuku unavailable)"
            )
        }

        val shellConfig = shellFrameworkConfig()
        if (shellConfig.selectsEasierSpot(app)) {
            val gate = ensureSettingsFeatureGate()
            if (!gate.enabled) {
                return remember(
                    SharedConnectivityCapability.Blocked(
                        gate.detail ?: "Android Settings Shared Connectivity feature gate is disabled"
                    ),
                    "provider configured; Settings feature gate blocked"
                )
            }
            if (gate.changed) refreshSettingsPackage()
            return remember(
                SharedConnectivityCapability.ProviderAlreadyConfigured,
                if (gate.changed) {
                    "provider configured; Shizuku enabled the Settings feature gate"
                } else {
                    "framework already configured"
                }
            ).also {
                LogUtils.diag(TAG, "Shared Connectivity provider is already configured")
            }
        }

        // Some ROMs or companion modules may ship a mutable EasierSpot RRO. Ordinary shell-backed
        // Shizuku can enable one of those even though it cannot fabricate a new framework overlay.
        val overlayList = PrivilegedShellClient.execute(
            arrayOf("/system/bin/cmd", "overlay", "list", "--user", OVERLAY_USER),
            4_000L
        )
        val candidates = parseEasierSpotOverlayCandidates(overlayList.stdout)
        for (candidate in candidates) {
            val changed = !candidate.enabled
            if (changed) {
                val enabled = PrivilegedShellClient.execute(
                    arrayOf(
                        "/system/bin/cmd", "overlay", "enable", "--user", OVERLAY_USER,
                        candidate.packageName
                    ),
                    4_000L
                )
                if (enabled.exitCode != 0) continue
            }

            // Best effort only. Resource lookup below is the authoritative success check.
            PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/cmd", "overlay", "set-priority", "--user", OVERLAY_USER,
                    candidate.packageName, "highest"
                ),
                4_000L
            )

            val verified = shellFrameworkConfig()
            if (verified.selectsEasierSpot(app)) {
                val gate = ensureSettingsFeatureGate()
                if (gate.enabled) {
                    if (changed || gate.changed) refreshSettingsPackage()
                    return remember(
                        SharedConnectivityCapability.ProviderActivated,
                        "Shizuku enabled an installed EasierSpot resource overlay"
                    ).also {
                        LogUtils.i(TAG, "Activated Shared Connectivity through ${candidate.packageName}")
                    }
                }
            }

            if (changed) {
                PrivilegedShellClient.execute(
                    arrayOf(
                        "/system/bin/cmd", "overlay", "disable", "--user", OVERLAY_USER,
                        candidate.packageName
                    ),
                    4_000L
                )
            }
        }

        val uidResult = PrivilegedShellClient.execute(arrayOf("/system/bin/id", "-u"), 4_000L)
        val privilegedUid = if (uidResult.exitCode == 0) parsePrivilegedUid(uidResult.stdout) else null
        lastPrivilegedUid = privilegedUid
        if (privilegedUid != 0) {
            return remember(
                SharedConnectivityCapability.Blocked(
                    buildString {
                        append("Offline native Wi-Fi picker entries require EasierSpot to be Android's ")
                        append("Shared Connectivity provider. Shizuku is connected")
                        if (privilegedUid != null) append(" as uid=$privilegedUid")
                        append(", but Android reserves runtime framework-overlay fabrication for root. ")
                        append("EasierSpot will use its scoped picker companion instead.")
                    }
                ),
                if (privilegedUid == null) {
                    "Shizuku available; privileged UID could not be determined"
                } else {
                    "Shizuku shell uid=$privilegedUid; native provider injection is root-only"
                }
            )
        }

        val fabrication = activateRootFabricatedProvider(app)
        if (!fabrication.success) {
            disableRootFabricatedProvider(app)
            return remember(
                SharedConnectivityCapability.Blocked(
                    fabrication.detail ?: "Root provider overlay activation failed"
                ),
                "root Shizuku provider injection failed"
            )
        }

        val verified = shellFrameworkConfig()
        if (!verified.selectsEasierSpot(app)) {
            disableRootFabricatedProvider(app)
            return remember(
                SharedConnectivityCapability.Blocked(
                    "Root overlays were created but Android did not resolve EasierSpot as the Shared Connectivity provider"
                ),
                "root Shizuku provider injection failed verification"
            )
        }

        val gate = ensureSettingsFeatureGate()
        if (!gate.enabled) {
            disableRootFabricatedProvider(app)
            return remember(
                SharedConnectivityCapability.Blocked(
                    gate.detail ?: "Could not enable Android Settings Shared Connectivity feature gate"
                ),
                "root provider injected; Settings feature gate blocked"
            )
        }

        refreshSettingsPackage()
        return remember(
            SharedConnectivityCapability.ProviderActivated,
            "root-backed Shizuku fabricated and verified framework provider overlays"
        ).also {
            LogUtils.i(TAG, "Activated Shared Connectivity through verified root fabricated overlays")
        }
    }

    private fun remember(
        capability: SharedConnectivityCapability,
        method: String
    ): SharedConnectivityCapability {
        lastCapability = capability
        lastActivationMethod = method
        return capability
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
            arrayOf(
                "/system/bin/cmd", "overlay", "lookup", "--user", OVERLAY_USER,
                "android", "android:$type/$name"
            ),
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

    private fun readSettingsFeatureGate(): Boolean? {
        val result = PrivilegedShellClient.execute(
            arrayOf(
                "/system/bin/cmd", "device_config", "get",
                DEVICE_CONFIG_NAMESPACE, DEVICE_CONFIG_KEY
            ),
            4_000L
        )
        return if (result.exitCode == 0) parseDeviceConfigBoolean(result.stdout) else null
    }

    private fun ensureSettingsFeatureGate(): FeatureGateResult {
        if (readSettingsFeatureGate() == true) return FeatureGateResult(enabled = true, changed = false)
        val result = PrivilegedShellClient.execute(
            arrayOf(
                "/system/bin/cmd", "device_config", "put",
                DEVICE_CONFIG_NAMESPACE, DEVICE_CONFIG_KEY, "true"
            ),
            4_000L
        )
        if (result.exitCode != 0) {
            return FeatureGateResult(
                enabled = false,
                changed = false,
                detail = "Could not enable Settings Shared Connectivity feature: " +
                    result.stderr.ifBlank { result.stdout }
            )
        }
        return if (readSettingsFeatureGate() == true) {
            FeatureGateResult(enabled = true, changed = true)
        } else {
            FeatureGateResult(
                enabled = false,
                changed = false,
                detail = "Android ignored the Shared Connectivity Settings feature override"
            )
        }
    }

    private fun activateRootFabricatedProvider(context: Context): FabricationResult {
        for (value in sharedConnectivityFabricatedValues(context.packageName, SERVICE_ACTION)) {
            val fabricated = PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/cmd", "overlay", "fabricate",
                    "--user", OVERLAY_USER,
                    "--target", "android",
                    "--name", value.name,
                    value.resourceName,
                    value.type,
                    value.value
                ),
                4_000L
            )
            if (fabricated.exitCode != 0) {
                return FabricationResult(
                    success = false,
                    detail = "Android rejected root overlay ${value.name}: " +
                        fabricated.stderr.ifBlank { fabricated.stdout }
                )
            }

            val identifier = "$FABRICATED_OVERLAY_PACKAGE:${value.name}"
            val enabled = PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/cmd", "overlay", "enable", "--user", OVERLAY_USER, identifier
                ),
                4_000L
            )
            if (enabled.exitCode != 0) {
                return FabricationResult(
                    success = false,
                    detail = "Could not enable root overlay ${value.name}: " +
                        enabled.stderr.ifBlank { enabled.stdout }
                )
            }

            // Fabricated overlays are mutable. Raising the new entry is best-effort because some
            // OEM overlay-manager implementations do not accept named identifiers here. Verification
            // after all three resources are installed is authoritative.
            PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/cmd", "overlay", "set-priority", "--user", OVERLAY_USER,
                    identifier, "highest"
                ),
                4_000L
            )
        }
        return FabricationResult(success = true)
    }

    private fun disableRootFabricatedProvider(context: Context) {
        sharedConnectivityFabricatedValues(context.packageName, SERVICE_ACTION).forEach { value ->
            PrivilegedShellClient.execute(
                arrayOf(
                    "/system/bin/cmd", "overlay", "disable", "--user", OVERLAY_USER,
                    "$FABRICATED_OVERLAY_PACKAGE:${value.name}"
                ),
                4_000L
            )
        }
    }

    private fun refreshSettingsPackage() {
        // SharedConnectivityRepository caches SharedConnectivityManager when Settings starts. If
        // provider resources or its DeviceConfig gate changed, restart only Settings so the next
        // Wi-Fi picker open rebuilds that manager against the verified provider configuration.
        val result = PrivilegedShellClient.execute(
            arrayOf("/system/bin/am", "force-stop", "--user", OVERLAY_USER, SETTINGS_PACKAGE),
            4_000L
        )
        if (result.exitCode != 0) {
            LogUtils.w(
                TAG,
                "Could not refresh Android Settings after Shared Connectivity activation: " +
                    result.stderr.ifBlank { result.stdout }
            )
        }
    }

    private data class OverlayCandidate(val packageName: String, val enabled: Boolean)

    private fun parseEasierSpotOverlayCandidates(output: String): List<OverlayCandidate> =
        output.lineSequence().mapNotNull { line ->
            val match = Regex("""^\s*\[([ xX])\]\s+([A-Za-z0-9_.:-]+)\s*$""").find(line)
                ?: return@mapNotNull null
            val packageName = match.groupValues[2]
            if (!packageName.contains("easierspot", ignoreCase = true)) return@mapNotNull null
            OverlayCandidate(packageName, match.groupValues[1].equals("x", ignoreCase = true))
        }.toList()

    private fun capabilityLabel(capability: SharedConnectivityCapability): String = when (capability) {
        SharedConnectivityCapability.ApiUnavailable -> "Unavailable on Android 12–13"
        SharedConnectivityCapability.ProviderAlreadyConfigured -> "Active"
        SharedConnectivityCapability.ProviderActivated -> "Active (provider activated)"
        is SharedConnectivityCapability.Blocked -> "Blocked: ${capability.reason}"
    }
}
