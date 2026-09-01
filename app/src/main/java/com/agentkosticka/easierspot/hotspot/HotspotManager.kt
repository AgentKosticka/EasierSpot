package com.agentkosticka.easierspot.hotspot

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.IIntResultListener
import android.net.wifi.IWifiManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.RemoteException
import android.os.ResultReceiver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.privileged.PrivilegedShellClient
import com.agentkosticka.easierspot.privileged.ShizukuStateMonitor
import com.agentkosticka.easierspot.util.LogUtils
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlinx.coroutines.delay

/**
 * Diagnostic result for hotspot configuration testing.
 */
data class HotspotDiagnostics(
    val shizukuRunning: Boolean,
    val shizukuPermissionGranted: Boolean,
    val wifiBinderObtained: Boolean,
    val wifiManagerObtained: Boolean,
    val softApConfigObtained: Boolean,
    val ssidExtracted: String?,
    val passphraseExtracted: Boolean,
    val errorMessage: String?
)

data class WifiConnectResult(
    val accepted: Boolean,
    val detail: String
)

data class WifiVerificationResult(
    val authoritative: Boolean,
    val connectedToTarget: Boolean,
    val connectedSsid: String?,
    val hasInternetCapability: Boolean,
    val validated: Boolean,
    val detail: String
)

sealed interface HotspotActivationState {
    data object Off : HotspotActivationState
    data object Requesting : HotspotActivationState
    data object Enabling : HotspotActivationState
    data object Ready : HotspotActivationState
    data class Failed(val detail: String) : HotspotActivationState
}

sealed interface WifiJoinResult {
    data class Accepted(val detail: String) : WifiJoinResult
    data class Rejected(val detail: String) : WifiJoinResult
    data class Verified(val ssid: String) : WifiJoinResult
    data class Unsupported(val detail: String) : WifiJoinResult
}

internal data class WifiStatusObservation(
    val ssid: String,
    val connected: Boolean,
    val hasInternetCapability: Boolean,
    val validated: Boolean
)

internal fun parseWifiStatus(output: String): List<WifiStatusObservation> =
    output.split("WifiInfo:").drop(1).mapNotNull { section ->
        val rawSsid = Regex("""SSID:\s*(\"(?:\\.|[^\"])*\"|[^,\r\n]+)""")
            .find(section)
            ?.groupValues
            ?.getOrNull(1)
            ?: return@mapNotNull null
        val ssid = rawSsid.trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        val completed = section.contains("Supplicant state: COMPLETED", ignoreCase = true)
        val ipAddress = Regex("""IP:\s*([^,\s]+)""", RegexOption.IGNORE_CASE)
            .find(section)
            ?.groupValues
            ?.getOrNull(1)
            ?.removePrefix("/")
        val hasIpAddress = !ipAddress.isNullOrBlank() &&
            ipAddress != "0.0.0.0" &&
            !ipAddress.equals("null", ignoreCase = true)
        WifiStatusObservation(
            ssid = ssid,
            connected = completed && hasIpAddress,
            hasInternetCapability = Regex("""\bINTERNET\b""").containsMatchIn(section),
            validated = Regex("""\bVALIDATED\b""").containsMatchIn(section)
        )
    }

class HotspotManager(private val context: Context) {
    companion object {
        private const val TAG = "HotspotManager"
        private const val SHELL_PACKAGE_NAME = "com.android.shell"
        private const val TETHERING_TYPE_WIFI = 0
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
    }

    // Cached IWifiManager for reuse
    private var cachedWifiManager: IWifiManager? = null

    fun isShizukuAvailable(): Boolean = isShizukuReady()

    /**
     * Add the supplied network as a normal saved network and actively switch the whole device to
     * it. The platform shell command uses WifiService.connect rather than an app-scoped request.
     */
    fun connectDeviceToWifi(credentials: HotspotCredentials): WifiConnectResult {
        if (!isShizukuReady()) {
            return WifiConnectResult(false, "Shizuku is unavailable")
        }

        enableClientWifi()
        val current = verifyConnectedWifi(credentials.ssid)
        if (current.authoritative && current.connectedToTarget) {
            return WifiConnectResult(true, "Already connected to ${credentials.ssid}")
        }
        val security = when (credentials.securityType) {
            HotspotCredentials.SecurityType.OPEN -> "open"
            HotspotCredentials.SecurityType.WPA3_SAE -> "wpa3"
            HotspotCredentials.SecurityType.WPA2_PSK,
            HotspotCredentials.SecurityType.WPA3_TRANSITION -> "wpa2"
        }
        val command = buildList {
            add("/system/bin/cmd")
            add("wifi")
            add("connect-network")
            add(credentials.ssid)
            add(security)
            if (security != "open") add(credentials.password)
            if (credentials.isHidden) add("-h")
        }.toTypedArray()
        val result = runShizukuCommand(command)
        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        val rejectedByWifiService = combined.contains("failed", ignoreCase = true) ||
            combined.contains("error", ignoreCase = true) ||
            combined.contains("unknown command", ignoreCase = true)
        val connectResult = WifiConnectResult(
            accepted = result.exitCode == 0 && !rejectedByWifiService,
            detail = combined.ifBlank { "exit=${result.exitCode}" }
        )
        if (connectResult.accepted) requestSuggestionReevaluation()
        return connectResult
    }

    fun enableClientWifi(): WifiConnectResult = runWifiShellCommand(
        arrayOf("/system/bin/cmd", "wifi", "set-wifi-enabled", "enabled")
    )

    fun reconnectClientWifi(): WifiConnectResult = runWifiShellCommand(
        arrayOf("/system/bin/cmd", "wifi", "reconnect")
    )

    /**
     * Fast-path an app-owned suggestion without creating a saved network. Android still owns the
     * final network selection; Shizuku only enables Wi-Fi, approves this app's suggestions,
     * requests a scan, and asks WifiService to reconsider its selector. The existing network is
     * deliberately preserved until Android has associated with the target.
     */
    fun prepareSuggestionSelection(expectedSsid: String): WifiConnectResult {
        if (!isShizukuReady()) return WifiConnectResult(false, "Shizuku is unavailable")
        enableClientWifi()
        runWifiShellCommand(
            arrayOf(
                "/system/bin/cmd",
                "wifi",
                "network-suggestions-set-user-approved",
                context.packageName,
                "yes"
            )
        )
        val current = verifyConnectedWifi(expectedSsid)
        if (current.authoritative && current.connectedToTarget) {
            return WifiConnectResult(true, "Already connected to $expectedSsid")
        }
        val scan = requestSuggestionReevaluation()
        val reconnect = reconnectClientWifi()
        return WifiConnectResult(
            accepted = scan.accepted || reconnect.accepted,
            detail = listOf(scan.detail, reconnect.detail).filter { it.isNotBlank() }.joinToString("; ")
        )
    }

    /**
     * Ask Android's Wi-Fi selector to immediately reconsider visible networks. This keeps the
     * WifiNetworkSuggestion owned by EasierSpot; unlike connect-network it does not create a
     * shell-owned saved network. Android still makes the final selection.
     */
    fun requestSuggestionReevaluation(): WifiConnectResult = runWifiShellCommand(
        arrayOf("/system/bin/cmd", "wifi", "start-scan")
    )

    /**
     * Give an already-connected Wi-Fi network the maximum shell-exposed score. Some ROMs keep
     * cellular as the default route after joining a suggestion; this nudges ConnectivityService
     * toward making Wi-Fi the device-wide default without binding only this app to the network.
     */
    fun preferConnectedWifiAsDefault(): WifiConnectResult = runWifiShellCommand(
        arrayOf("/system/bin/cmd", "wifi", "set-connected-score", "60")
    )

    /**
     * Read WifiService's own connection state through the shell identity. This bypasses the
     * WifiInfo redaction and stale ConnectivityManager callback behavior seen on some OEM ROMs.
     */
    fun verifyConnectedWifi(expectedSsid: String): WifiVerificationResult {
        if (!isShizukuReady()) {
            return WifiVerificationResult(
                authoritative = false,
                connectedToTarget = false,
                connectedSsid = null,
                hasInternetCapability = false,
                validated = false,
                detail = "Shizuku is unavailable"
            )
        }

        val result = runShizukuCommand(arrayOf("/system/bin/cmd", "wifi", "status"))
        if (result.exitCode != 0 || result.stdout.isBlank()) {
            return WifiVerificationResult(
                authoritative = false,
                connectedToTarget = false,
                connectedSsid = null,
                hasInternetCapability = false,
                validated = false,
                detail = result.stderr.ifBlank { "wifi status exit=${result.exitCode}" }
            )
        }

        val observations = parseWifiStatus(result.stdout)

        if (observations.isEmpty()) {
            return WifiVerificationResult(
                authoritative = false,
                connectedToTarget = false,
                connectedSsid = null,
                hasInternetCapability = false,
                validated = false,
                detail = "Wi-Fi status format was not recognized"
            )
        }

        val target = observations.firstOrNull { observation ->
            observation.connected && observation.ssid == expectedSsid
        }
        val connectedObservation = target ?: observations.firstOrNull { it.connected }
        val connectedSsid = connectedObservation?.ssid
        return WifiVerificationResult(
            authoritative = true,
            connectedToTarget = target != null,
            connectedSsid = connectedSsid,
            hasInternetCapability = target?.hasInternetCapability == true,
            validated = target?.validated == true,
            detail = if (target != null) {
                "WifiService confirms $expectedSsid"
            } else {
                "WifiService reports ${connectedSsid ?: "no connected Wi-Fi"}"
            }
        )
    }

    private fun runWifiShellCommand(command: Array<String>): WifiConnectResult {
        if (!isShizukuReady()) {
            return WifiConnectResult(false, "Shizuku is unavailable")
        }

        val result = runShizukuCommand(command)
        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        val rejected = combined.contains("failed", ignoreCase = true) ||
            combined.contains("error", ignoreCase = true) ||
            combined.contains("unknown command", ignoreCase = true) ||
            combined.contains("not supported", ignoreCase = true)
        return WifiConnectResult(
            accepted = result.exitCode == 0 && !rejected,
            detail = combined.ifBlank { "exit=${result.exitCode}" }
        )
    }

    /**
     * Get the current hotspot configuration (SSID + password)
     * Uses Shizuku binder wrapper to access hidden WifiManager APIs
     */
    fun getHotspotCredentials(): HotspotCredentials? {
        LogUtils.i(TAG, "getHotspotCredentials() called")

        if (!isShizukuReady()) {
            LogUtils.w(TAG, "Shizuku unavailable or permission denied")
            return null
        }

        // Try Shizuku AIDL approach first (most reliable)
        val shizukuResult = getHotspotCredentialsViaShizuku()
        if (shizukuResult != null) {
            LogUtils.i(TAG, "Retrieved hotspot credentials through the Wi-Fi binder")
            return shizukuResult
        }

        // Fallback to shell command
        val shellResult = getHotspotCredentialsViaShell()
        if (shellResult != null) {
            LogUtils.i(TAG, "Retrieved hotspot credentials through the privileged fallback")
            return shellResult
        }

        // Last resort: try reflection on standard WifiManager (may not work without elevation)
        LogUtils.w(TAG, "Shizuku methods failed, trying reflection fallback")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            getHotspotCredentialsOreo(wifiManager)
        } catch (e: Exception) {
            LogUtils.e(TAG, "All credential retrieval methods failed", e)
            null
        }
    }

    /**
     * Run diagnostics on hotspot config reading - useful for in-app testing.
     * Returns detailed information about each step of the process.
     */
    fun getHotspotDiagnostics(): HotspotDiagnostics {
        var shizukuRunning = false
        var shizukuPermissionGranted = false
        var wifiBinderObtained = false
        var wifiManagerObtained = false
        var softApConfigObtained = false
        var ssidExtracted: String? = null
        var passphraseExtracted = false
        var errorMessage: String? = null

        try {
            // Step 1: Check Shizuku status
            shizukuRunning = try {
                Shizuku.pingBinder()
            } catch (e: Exception) {
                errorMessage = "Shizuku.pingBinder() failed: ${e.message}"
                false
            }

            if (!shizukuRunning) {
                errorMessage = errorMessage ?: "Shizuku is not running"
                return HotspotDiagnostics(
                    shizukuRunning = false,
                    shizukuPermissionGranted = false,
                    wifiBinderObtained = false,
                    wifiManagerObtained = false,
                    softApConfigObtained = false,
                    ssidExtracted = null,
                    passphraseExtracted = false,
                    errorMessage = errorMessage
                )
            }

            // Step 2: Check Shizuku permission
            shizukuPermissionGranted = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                errorMessage = "Shizuku.checkSelfPermission() failed: ${e.message}"
                false
            }

            if (!shizukuPermissionGranted) {
                errorMessage = errorMessage ?: "Shizuku permission not granted"
                return HotspotDiagnostics(
                    shizukuRunning = true,
                    shizukuPermissionGranted = false,
                    wifiBinderObtained = false,
                    wifiManagerObtained = false,
                    softApConfigObtained = false,
                    ssidExtracted = null,
                    passphraseExtracted = false,
                    errorMessage = errorMessage
                )
            }

            // Step 3: Get Wi-Fi binder
            val wifiBinder = try {
                SystemServiceHelper.getSystemService("wifi")
            } catch (e: Exception) {
                errorMessage = "Failed to get wifi service: ${e.message}"
                null
            }

            wifiBinderObtained = wifiBinder != null
            if (!wifiBinderObtained) {
                errorMessage = errorMessage ?: "Failed to obtain wifi binder"
                return HotspotDiagnostics(
                    shizukuRunning = true,
                    shizukuPermissionGranted = true,
                    wifiBinderObtained = false,
                    wifiManagerObtained = false,
                    softApConfigObtained = false,
                    ssidExtracted = null,
                    passphraseExtracted = false,
                    errorMessage = errorMessage
                )
            }

            // Step 4: Get IWifiManager interface
            val wifiManager = try {
                val wrappedBinder = ShizukuBinderWrapper(wifiBinder!!)
                IWifiManager.Stub.asInterface(wrappedBinder)
            } catch (e: Exception) {
                errorMessage = "IWifiManager.Stub.asInterface() failed: ${e.message}"
                null
            }

            wifiManagerObtained = wifiManager != null
            if (!wifiManagerObtained) {
                errorMessage = errorMessage ?: "Failed to get IWifiManager interface"
                return HotspotDiagnostics(
                    shizukuRunning = true,
                    shizukuPermissionGranted = true,
                    wifiBinderObtained = true,
                    wifiManagerObtained = false,
                    softApConfigObtained = false,
                    ssidExtracted = null,
                    passphraseExtracted = false,
                    errorMessage = errorMessage
                )
            }

            // Step 5: Get SoftApConfiguration
            val softApConfig = try {
                wifiManager!!.softApConfiguration
            } catch (e: Exception) {
                errorMessage = "getSoftApConfiguration() failed: ${e.message}"
                null
            }

            softApConfigObtained = softApConfig != null
            if (!softApConfigObtained) {
                errorMessage = errorMessage ?: "SoftApConfiguration is null"
                return HotspotDiagnostics(
                    shizukuRunning = true,
                    shizukuPermissionGranted = true,
                    wifiBinderObtained = true,
                    wifiManagerObtained = true,
                    softApConfigObtained = false,
                    ssidExtracted = null,
                    passphraseExtracted = false,
                    errorMessage = errorMessage
                )
            }

            // Step 6: Extract SSID and passphrase using reflection
            try {
                val result = extractCredentialsViaReflection(softApConfig!!)
                ssidExtracted = result?.ssid
                passphraseExtracted = !result?.password.isNullOrEmpty()

                if (ssidExtracted.isNullOrEmpty()) {
                    errorMessage = "SSID extraction returned empty string"
                }
            } catch (e: Exception) {
                errorMessage = "Credential extraction failed: ${e.message}"
            }

        } catch (e: Exception) {
            errorMessage = "Unexpected error: ${e.message}"
        }

        return HotspotDiagnostics(
            shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
            wifiManagerObtained, softApConfigObtained, ssidExtracted,
            passphraseExtracted, errorMessage
        )
    }

    /**
     * Get hotspot credentials using Shizuku's binder wrapper to call IWifiManager directly.
     * Uses typed AIDL stubs for IWifiManager, then reflection for hidden SoftApConfiguration methods.
     */
    private fun getHotspotCredentialsViaShizuku(): HotspotCredentials? {
        return try {
            LogUtils.diag(TAG, "Attempting Shizuku AIDL approach...")
            
            // Get or create the IWifiManager interface
            val wifiManager = getOrCreateWifiManager()
            if (wifiManager == null) {
                LogUtils.w(TAG, "Failed to get IWifiManager")
                return null
            }
            
            // Get SoftApConfiguration
            LogUtils.diag(TAG, "Calling getSoftApConfiguration...")
            val softApConfig = wifiManager.softApConfiguration
            
            if (softApConfig == null) {
                LogUtils.w(TAG, "getSoftApConfiguration returned null")
                return null
            }
            LogUtils.diag(TAG, "Got SoftApConfiguration: ${softApConfig.javaClass.name}")
            
            // Extract credentials using reflection on SoftApConfiguration hidden methods
            extractCredentialsViaReflection(softApConfig)
            
        } catch (e: RemoteException) {
            LogUtils.e(TAG, "RemoteException calling IWifiManager: ${e.message}")
            cachedWifiManager = null // Clear cache on error
            null
        } catch (e: Exception) {
            LogUtils.e(TAG, "Exception in Shizuku AIDL approach: ${e.message}", e)
            cachedWifiManager = null
            null
        }
    }
    
    /**
     * Extract SSID and passphrase from SoftApConfiguration using reflection.
     * This avoids the need for Refine plugin bytecode transformation.
     */
    private fun extractCredentialsViaReflection(softApConfig: Any): HotspotCredentials? {
        return try {
            val configClass = softApConfig.javaClass
            LogUtils.diag(TAG, "SoftApConfiguration class: ${configClass.name}")
            
            // Extract SSID - try WifiSsid first (Android 13+), then fallback to getSsid()
            val ssid = extractSsidViaReflection(softApConfig, configClass)
            
            // Extract passphrase
            val passphrase = try {
                val getPassphraseMethod = configClass.getMethod("getPassphrase")
                getPassphraseMethod.invoke(softApConfig) as? String ?: ""
            } catch (e: Exception) {
                LogUtils.d(TAG, "getPassphrase() failed: ${e.message}")
                ""
            }
            
            LogUtils.diag(TAG, "Extracted hotspot configuration; passphrase=${if (passphrase.isEmpty()) "empty" else "set"}")
            
            if (ssid.isNotEmpty()) {
                HotspotCredentials(
                    ssid = ssid,
                    password = passphrase,
                    securityType = extractSecurityType(softApConfig, configClass)
                )
            } else {
                LogUtils.w(TAG, "SSID is empty after extraction")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "extractCredentialsViaReflection failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * Extract SSID from SoftApConfiguration.
     * On Android 13+, uses getWifiSsid().getBytes() to handle non-UTF8 SSIDs.
     * Falls back to deprecated getSsid() on older versions.
     */
    private fun extractSsidViaReflection(softApConfig: Any, configClass: Class<*>): String {
        // Try getWifiSsid() first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val getWifiSsidMethod = configClass.getMethod("getWifiSsid")
                val wifiSsid = getWifiSsidMethod.invoke(softApConfig)
                if (wifiSsid != null) {
                    LogUtils.diag(TAG, "WifiSsid object: $wifiSsid (class: ${wifiSsid.javaClass.name})")
                    // Call getBytes() on WifiSsid to get raw SSID bytes
                    val getBytesMethod = wifiSsid.javaClass.getMethod("getBytes")
                    val ssidBytes = getBytesMethod.invoke(wifiSsid) as? ByteArray
                    if (ssidBytes != null && ssidBytes.isNotEmpty()) {
                        val ssid = ssidBytes.decodeToString()
                        LogUtils.diag(TAG, "Read SSID through WifiSsid bytes")
                        return ssid
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(TAG, "getWifiSsid() approach failed: ${e.message}")
            }
        }
        
        // Fallback to deprecated getSsid()
        return try {
            val getSsidMethod = configClass.getMethod("getSsid")
            val ssid = getSsidMethod.invoke(softApConfig) as? String ?: ""
            LogUtils.diag(TAG, "Read SSID through getSsid")
            ssid
        } catch (e: Exception) {
            LogUtils.w(TAG, "getSsid() failed: ${e.message}")
            ""
        }
    }

    private fun extractSecurityType(
        softApConfig: Any,
        configClass: Class<*>
    ): HotspotCredentials.SecurityType {
        val rawType = runCatching {
            configClass.getMethod("getSecurityType").invoke(softApConfig) as Int
        }.getOrNull()
        return when (rawType) {
            0 -> HotspotCredentials.SecurityType.OPEN
            2 -> HotspotCredentials.SecurityType.WPA3_TRANSITION
            3 -> HotspotCredentials.SecurityType.WPA3_SAE
            else -> HotspotCredentials.SecurityType.WPA2_PSK
        }
    }

    /**
     * Get or create the IWifiManager interface via Shizuku.
     */
    private fun getOrCreateWifiManager(): IWifiManager? {
        // Return cached instance if available
        cachedWifiManager?.let { return it }

        return try {
            // Get the WiFi service binder via Shizuku
            val wifiBinder = SystemServiceHelper.getSystemService("wifi")
            if (wifiBinder == null) {
                LogUtils.e(TAG, "Failed to get wifi service binder")
                return null
            }
            LogUtils.diag(TAG, "Got wifi service binder")
            
            // Wrap with Shizuku to get elevated permissions
            val wrappedBinder = ShizukuBinderWrapper(wifiBinder)
            
            // Use the typed stub class instead of reflection
            val wifiManager = IWifiManager.Stub.asInterface(wrappedBinder)
            LogUtils.diag(TAG, "Got IWifiManager via typed stub")
            
            cachedWifiManager = wifiManager
            wifiManager
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to create IWifiManager: ${e.message}", e)
            null
        }
    }

    /**
     * Get hotspot credentials via shell command as fallback
     * Uses Shizuku's ShellBinder to execute privileged shell commands
     */
    private fun getHotspotCredentialsViaShell(): HotspotCredentials? {
        return try {
            LogUtils.diag(TAG, "Attempting shell command approach...")

            val result = runShizukuCommand(arrayOf("cmd", "wifi", "get-softap-config"))
            if (result.exitCode != 0 || result.stdout.isBlank()) {
                LogUtils.w(TAG, "Shell command failed or returned empty")
                return null
            }
            val output = result.stdout
            
            // Parse output - format varies by Android version
            // Common format: "ssid=MyHotspot\npassphrase=MyPassword\n..."
            var ssid = ""
            var passphrase = ""
            
            output.lines().forEach { line ->
                when {
                    line.startsWith("ssid=") -> ssid = line.removePrefix("ssid=").trim()
                    line.startsWith("SSID:") -> ssid = line.removePrefix("SSID:").trim()
                    line.contains("SSID") && line.contains(":") -> {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) ssid = parts[1].trim()
                    }
                    line.startsWith("passphrase=") -> passphrase = line.removePrefix("passphrase=").trim()
                    line.startsWith("Passphrase:") -> passphrase = line.removePrefix("Passphrase:").trim()
                    line.startsWith("psk=") -> passphrase = line.removePrefix("psk=").trim()
                    line.contains("Passphrase") && line.contains(":") -> {
                        val parts = line.split(":", limit = 2)
                        if (parts.size == 2) passphrase = parts[1].trim()
                    }
                }
            }
            
            if (ssid.isNotEmpty()) {
                HotspotCredentials(ssid, passphrase)
            } else {
                LogUtils.w(TAG, "Could not parse SSID from shell output")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Shell command approach failed: ${e.message}")
            null
        }
    }

    private fun getHotspotCredentialsOreo(wifiManager: WifiManager): HotspotCredentials? {
        return try {
            val getSoftApConfigMethod = wifiManager.javaClass.getMethod("getSoftApConfiguration")
            val softApConfig = getSoftApConfigMethod.invoke(wifiManager)

            if (softApConfig != null) {
                extractCredentialsViaReflection(softApConfig)
            } else {
                LogUtils.w(TAG, "SoftApConfiguration is null (reflection)")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to get SoftApConfiguration via reflection: ${e.message}")
            null
        }
    }

//    private fun getHotspotCredentialsLegacy(wifiManager: WifiManager): HotspotCredentials? {
//        return try {
//            val getWifiApConfigurationMethod = wifiManager.javaClass.getMethod("getWifiApConfiguration")
//            val wifiConfig = getWifiApConfigurationMethod.invoke(wifiManager)
//
//            if (wifiConfig != null) {
//                val ssidField = wifiConfig.javaClass.getDeclaredField("SSID")
//                ssidField.isAccessible = true
//                val ssid = ssidField.get(wifiConfig) as? String ?: ""
//
//                val pskField = wifiConfig.javaClass.getDeclaredField("preSharedKey")
//                pskField.isAccessible = true
//                val passphrase = pskField.get(wifiConfig) as? String ?: ""
//
//                if (ssid.isNotEmpty()) {
//                    HotspotCredentials(ssid, passphrase)
//                } else {
//                    null
//                }
//            } else {
//                LogUtils.w(TAG, "WifiConfiguration is null")
//                null
//            }
//        } catch (e: Exception) {
//            LogUtils.e(TAG, "Failed to get WifiConfiguration: ${e.message}")
//            null
//        }
//    }

    fun isHotspotEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val privilegedState = getPrivilegedHotspotState()
        if (privilegedState == WIFI_AP_STATE_ENABLED) {
            LogUtils.diag(TAG, "isHotspotEnabled: true (Shizuku IWifiManager)")
            return true
        }

        val enabledByIsWifiApEnabled = try {
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            (method.invoke(wifiManager) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
        if (enabledByIsWifiApEnabled) {
            LogUtils.diag(TAG, "isHotspotEnabled: true (isWifiApEnabled)")
            return true
        }

        val enabledByApState = try {
            val method = wifiManager.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wifiManager) as? Int
            state == WIFI_AP_STATE_ENABLED
        } catch (_: Exception) {
            false
        }
        if (enabledByApState) {
            LogUtils.diag(TAG, "isHotspotEnabled: true (getWifiApState)")
            return true
        }

        val enabledByGlobalSetting = try {
            Settings.Global.getInt(context.contentResolver, "soft_ap_enabled", 0) == 1
        } catch (_: Exception) {
            false
        }
        if (enabledByGlobalSetting) {
            LogUtils.diag(TAG, "isHotspotEnabled: true (Settings.Global soft_ap_enabled)")
            return true
        }

        LogUtils.diag(TAG, "isHotspotEnabled: false")
        return false
    }

    /** Used only for ownership tracking; an enabling hotspot is not ready for credential use. */
    fun isHotspotStarting(): Boolean {
        if (getPrivilegedHotspotState() == WIFI_AP_STATE_ENABLING) return true
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return runCatching {
            val method = wifiManager.javaClass.getMethod("getWifiApState")
            (method.invoke(wifiManager) as? Int) == WIFI_AP_STATE_ENABLING
        }.getOrDefault(false)
    }

    private fun getPrivilegedHotspotState(): Int? {
        if (!isShizukuReady()) return null
        return try {
            getOrCreateWifiManager()?.wifiApEnabledState
        } catch (error: Exception) {
            LogUtils.d(TAG, "Privileged hotspot state unavailable: ${error.message}")
            cachedWifiManager = null
            null
        }
    }

    /**
     * Turn hotspot on - tries programmatic approach, falls back to user prompt
     * Returns true if hotspot is enabled (either we enabled it or it was already on)
     */
    fun startHotspot(): Boolean {
        LogUtils.diag(TAG, "startHotspot() called")

        if (isHotspotEnabled()) {
            LogUtils.diag(TAG, "Hotspot is already enabled")
            return true
        }

        if (!isShizukuReady()) {
            LogUtils.w(TAG, "Shizuku unavailable; cannot start hotspot programmatically")
            return false
        }

        val connectorAccepted = startTetheringViaConnector()
        if (!connectorAccepted && !startTetheringViaShell().accepted) {
            LogUtils.w(TAG, "Programmatic hotspot enable failed")
            return false
        }

        // A successful command only means the asynchronous request was accepted. The service
        // observes SoftAP state separately before it reads or transmits credentials.
        return true
    }

    /** Waits for actual SoftAP readiness; accepted privileged calls are not treated as success. */
    suspend fun ensureHotspotReady(
        timeoutMs: Long = 35_000L,
        onState: (HotspotActivationState) -> Unit = {}
    ): HotspotActivationState {
        if (isHotspotEnabled()) return HotspotActivationState.Ready.also(onState)
        if (!isShizukuReady()) {
            return HotspotActivationState.Failed("Shizuku is unavailable").also(onState)
        }
        onState(HotspotActivationState.Requesting)
        val preferredBackend = activationPreferences().getString(activationBackendKey(), null)
        var requestedBackend = "connector"
        val triedShellBackends = linkedSetOf<Int>()

        fun tryNextShell(preferredIndex: Int? = null): Boolean {
            val attempt = startTetheringViaShell(preferredIndex, triedShellBackends)
            if (attempt.index >= 0) triedShellBackends += attempt.index
            if (attempt.accepted) requestedBackend = "shell:${attempt.index}"
            return attempt.accepted
        }

        val preferredShell = preferredBackend
            ?.takeIf { it.startsWith("shell:") }
            ?.substringAfter(':')
            ?.toIntOrNull()
        var requestAccepted = if (preferredShell != null) tryNextShell(preferredShell) else {
            startTetheringViaConnector().also { if (it) requestedBackend = "connector" }
        }
        if (!requestAccepted) {
            requestAccepted = if (preferredShell != null) {
                startTetheringViaConnector().also { if (it) requestedBackend = "connector" }
            } else {
                tryNextShell()
            }
        }
        if (!requestAccepted) {
            return HotspotActivationState.Failed("Android rejected every supported hotspot start path")
                .also(onState)
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var observedProgress = false
        var fallbackAttempted = requestedBackend.startsWith("shell:")
        while (android.os.SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
            if (isHotspotEnabled()) {
                activationPreferences().edit { putString(activationBackendKey(), requestedBackend) }
                return HotspotActivationState.Ready.also(onState)
            }
            if (isHotspotStarting()) {
                observedProgress = true
                onState(HotspotActivationState.Enabling)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            if (!observedProgress && elapsed >= 3_000L && !fallbackAttempted) {
                fallbackAttempted = true
                tryNextShell()
            }
            delay(
                when {
                    elapsed < 2_000L -> 100L
                    elapsed < 5_000L -> 250L
                    else -> 1_000L
                }
            )
        }
        return HotspotActivationState.Failed("Timed out waiting for Android hotspot readiness")
            .also(onState)
    }

    private fun activationPreferences() =
        context.getSharedPreferences("hotspot_activation_backends_v1", Context.MODE_PRIVATE)

    private fun activationBackendKey(): String = "backend_${Build.FINGERPRINT.hashCode()}"

    /** Stop tethering only when the owning service has established that EasierSpot started it. */
    fun stopHotspot(): Boolean {
        if (!isHotspotEnabled()) return true
        if (!isShizukuReady()) return false
        return stopTetheringViaConnector() || stopTetheringViaShell()
    }

    /**
     * Get intent to open tethering settings directly (Android 8+)
     */
    fun getTetheringSettingsIntent(): Intent {
        val candidates = listOf(
            Intent("android.settings.TETHERING_SETTINGS"),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        val packageManager = context.packageManager
        for (candidate in candidates) {
            candidate.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val canResolve = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(candidate, PackageManager.ResolveInfoFlags.of(0)) != null
            } else {
                @SuppressLint("QueryPermissionsNeeded")
                candidate.resolveActivity(packageManager) != null
            }

            if (canResolve) return candidate
        }

        return Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun isShizukuReady(): Boolean = ShizukuStateMonitor.isReady()

    private data class ShellActivationAttempt(val accepted: Boolean, val index: Int)

    private fun startTetheringViaShell(
        preferredIndex: Int? = null,
        excludedIndexes: Set<Int> = emptySet()
    ): ShellActivationAttempt {
        val commands = listOf(
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start", "--type", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start"),
            arrayOf("/system/bin/cmd", "tethering", "start", "wifi"),
            arrayOf("/system/bin/cmd", "tethering", "start", "--type", "wifi")
        )
        val order = buildList {
            if (preferredIndex != null && preferredIndex in commands.indices) add(preferredIndex)
            addAll(commands.indices.filter { it != preferredIndex })
        }
        for (index in order) {
            if (index in excludedIndexes) continue
            val command = commands[index]
            val result = runShizukuCommand(command, timeoutSeconds = 2L)
            val output = "${result.stdout}\n${result.stderr}"
            val rejected = listOf(
                "failed",
                "error",
                "unknown command",
                "not supported",
                "exception"
            ).any { output.contains(it, ignoreCase = true) }
            if (result.exitCode == 0 && !rejected) {
                LogUtils.diag(TAG, "Hotspot start backend $index accepted the request")
                return ShellActivationAttempt(true, index)
            }
        }
        return ShellActivationAttempt(false, -1)
    }

    private fun startTetheringViaConnector(): Boolean {
        return try {
            val connector = getTetheringConnector() ?: return false
            val methods = connector.javaClass.methods
                .filter { it.name == "startTethering" }
                .sortedByDescending { scoreTetheringMethod(it) }

            for (method in methods) {
                val args = buildTetheringArgs(method, isStart = true) ?: continue
                runCatching {
                    method.invoke(connector, *args)
                    LogUtils.diag(TAG, "Connector startTethering invoked")
                    return true
                }
            }

            LogUtils.w(TAG, "No supported startTethering signature succeeded")
            false
        } catch (e: Exception) {
            LogUtils.e(TAG, "startTetheringViaConnector failed: ${e.message}")
            false
        }
    }

    private fun stopTetheringViaShell(): Boolean {
        val commands = listOf(
            arrayOf("cmd", "connectivity", "tether", "stop", "wifi"),
            arrayOf("cmd", "tethering", "stop", "wifi"),
            arrayOf("cmd", "wifi", "stop-softap")
        )
        return commands.any { command ->
            runShizukuCommand(command, timeoutSeconds = 2L).exitCode == 0
        }
    }

    private fun stopTetheringViaConnector(): Boolean {
        return try {
            val connector = getTetheringConnector() ?: return false
            connector.javaClass.methods
                .filter { it.name == "stopTethering" }
                .sortedByDescending { scoreTetheringMethod(it) }
                .any { method ->
                    val args = buildTetheringArgs(method, isStart = false) ?: return@any false
                    runCatching { method.invoke(connector, *args) }.isSuccess
                }
        } catch (error: Exception) {
            LogUtils.e(TAG, "stopTetheringViaConnector failed", error)
            false
        }
    }

//    private fun stopTetheringViaShell(): Boolean {
//        val commands = listOf(
//            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop", "wifi"),
//            arrayOf("cmd", "connectivity", "tether", "stop", "wifi"),
//            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop", "--type", "wifi"),
//            arrayOf("cmd", "connectivity", "tether", "stop", "--type", "wifi"),
//            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop"),
//            arrayOf("cmd", "connectivity", "tether", "stop"),
//            arrayOf("/system/bin/cmd", "tethering", "stop", "wifi"),
//            arrayOf("cmd", "tethering", "stop", "wifi"),
//            arrayOf("/system/bin/cmd", "tethering", "stop", "--type", "wifi"),
//            arrayOf("cmd", "tethering", "stop", "--type", "wifi"),
//            arrayOf("/system/bin/cmd", "wifi", "stop-softap"),
//            arrayOf("cmd", "wifi", "stop-softap"),
//            arrayOf("service", "call", "wifi", "49")
//        )
//        for (command in commands) {
//            val result = runShizukuCommand(command)
//            if (result.exitCode == 0) {
//                LogUtils.diag(TAG, "Hotspot stop command succeeded: ${command.joinToString(" ")}")
//                return true
//            }
//        }
//        return false
//    }
//
//    private fun stopTetheringViaConnector(): Boolean {
//        return try {
//            val connector = getTetheringConnector() ?: return false
//            val methods = connector.javaClass.methods
//                .filter { it.name == "stopTethering" }
//                .sortedByDescending { scoreTetheringMethod(it) }
//
//            for (method in methods) {
//                val args = buildTetheringArgs(method, isStart = false) ?: continue
//                runCatching {
//                    method.invoke(connector, *args)
//                    LogUtils.diag(TAG, "Connector stopTethering invoked")
//                    return true
//                }
//            }
//
//            LogUtils.w(TAG, "No supported stopTethering signature succeeded")
//            false
//        } catch (e: Exception) {
//            LogUtils.e(TAG, "stopTetheringViaConnector failed: ${e.message}")
//            false
//        }
//    }

    private fun scoreTetheringMethod(method: Method): Int {
        val params = method.parameterTypes
        return when {
            params.isNotEmpty() && params[0] == Int::class.javaPrimitiveType -> 100
            params.isNotEmpty() && params[0].name == "android.net.TetheringRequestParcel"-> 50
            else -> 10
        } + params.size
    }

    private fun buildTetheringArgs(method: Method, isStart: Boolean): Array<Any?>? {
        val args = mutableListOf<Any?>()
        var stringArgIndex = 0

        for (param in method.parameterTypes) {
            val value = when {
                param == Int::class.javaPrimitiveType -> TETHERING_TYPE_WIFI
                param == Boolean::class.javaPrimitiveType -> false
                param == String::class.java -> {
                    stringArgIndex += 1
                    if (stringArgIndex == 1) SHELL_PACKAGE_NAME else null
                }
                param.name == "android.os.ResultReceiver" -> {
                    ResultReceiver(Handler(Looper.getMainLooper()))
                }
                param.name == "android.net.TetheringRequestParcel" && isStart -> {
                    createTetheringRequestParcel()
                }
                param.name == "android.net.IIntResultListener" -> tetheringResultListener
                else -> {
                    null
                }
            }

            if (value == null && param.isPrimitive) {
                return null
            }
            if (
                value == null &&
                param.name != "android.net.IIntResultListener" &&
                !(param.name == "android.net.TetheringRequestParcel" && isStart) &&
                param != String::class.java
            ) {
                return null
            }
            args += value
        }

        return args.toTypedArray()
    }

    private fun createTetheringRequestParcel(): Any? {
        return runCatching {
            val builderClass = Class.forName($$"android.net.TetheringManager$TetheringRequest$Builder")
            val ctor = builderClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val builder = ctor.newInstance(TETHERING_TYPE_WIFI)

            runCatching {
                val entitlementUi = builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType)
                entitlementUi.invoke(builder, false)
            }

            val buildMethod = builderClass.getMethod("build")
            val request = buildMethod.invoke(builder)

            request.javaClass.methods.firstOrNull {
                it.name == "getParcel" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType.name == "android.net.TetheringRequestParcel"
            }?.invoke(request)
                ?: request.javaClass.declaredFields.firstOrNull {
                    it.type.name == "android.net.TetheringRequestParcel"
                }?.let {
                    it.isAccessible = true
                    it.get(request)
                }
        }.getOrElse {
            runCatching {
                @SuppressLint("PrivateApi")
                val parcelClass = Class.forName("android.net.TetheringRequestParcel")
                val parcel = parcelClass.getDeclaredConstructor().newInstance()
                parcelClass.declaredFields.firstOrNull {
                    it.name == "tetheringType" && it.type == Int::class.javaPrimitiveType
                }?.let {
                    it.isAccessible = true
                    it.setInt(parcel, TETHERING_TYPE_WIFI)
                }
                parcel
            }.getOrNull()
        }
    }

    private val tetheringResultListener = object : IIntResultListener.Stub() {
        override fun onResult(resultCode: Int) {
            if (resultCode == 0) {
                LogUtils.diag(TAG, "Tethering connector accepted the request")
            } else {
                LogUtils.w(TAG, "Tethering connector rejected the request (code=$resultCode)")
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun getTetheringConnector(): Any? {
        return try {
            val tetheringBinder = SystemServiceHelper.getSystemService("tethering")
            if (tetheringBinder == null) {
                LogUtils.e(TAG, "Could not get tethering system service binder")
                return null
            }
            val wrappedBinder = ShizukuBinderWrapper(tetheringBinder)
            val stubClass = Class.forName($$"android.net.ITetheringConnector$Stub")
            val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            asInterface.invoke(null, wrappedBinder)
        } catch (e: Exception) {
            LogUtils.e(TAG, "getTetheringConnector failed: ${e.message}")
            null
        }
    }

    private fun runShizukuCommand(
        command: Array<String>,
        timeoutSeconds: Long = 10L
    ): CommandResult {
        val result = PrivilegedShellClient.execute(command, timeoutSeconds * 1_000L)
        return CommandResult(result.exitCode, result.stdout, result.stderr)
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
