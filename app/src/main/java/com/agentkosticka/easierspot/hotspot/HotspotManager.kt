package com.agentkosticka.easierspot.hotspot

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.IWifiManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.RemoteException
import android.os.ResultReceiver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.agentkosticka.easierspot.data.model.HotspotCredentials
import com.agentkosticka.easierspot.util.LogUtils
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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
            LogUtils.i(TAG, "Got credentials via Shizuku: ssid=${shizukuResult.ssid}")
            return shizukuResult
        }

        // Fallback to shell command
        val shellResult = getHotspotCredentialsViaShell()
        if (shellResult != null) {
            LogUtils.i(TAG, "Got credentials via shell: ssid=${shellResult.ssid}")
            return shellResult
        }

        // Last resort: try reflection on standard WifiManager (may not work without elevation)
        LogUtils.w(TAG, "Shizuku methods failed, trying reflection fallback")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getHotspotCredentialsOreo(wifiManager)
            } else {
                getHotspotCredentialsLegacy(wifiManager)
            }
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
                    shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
                    wifiManagerObtained, softApConfigObtained, ssidExtracted,
                    passphraseExtracted, errorMessage
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
                    shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
                    wifiManagerObtained, softApConfigObtained, ssidExtracted,
                    passphraseExtracted, errorMessage
                )
            }

            // Step 3: Get wifi binder
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
                    shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
                    wifiManagerObtained, softApConfigObtained, ssidExtracted,
                    passphraseExtracted, errorMessage
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
                    shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
                    wifiManagerObtained, softApConfigObtained, ssidExtracted,
                    passphraseExtracted, errorMessage
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
                    shizukuRunning, shizukuPermissionGranted, wifiBinderObtained,
                    wifiManagerObtained, softApConfigObtained, ssidExtracted,
                    passphraseExtracted, errorMessage
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
            
            LogUtils.diag(TAG, "Extracted: ssid='$ssid', passphrase=${if (passphrase.isEmpty()) "(empty)" else "(set)"}")
            
            if (ssid.isNotEmpty()) {
                HotspotCredentials(ssid, passphrase)
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
                        LogUtils.diag(TAG, "Got SSID via WifiSsid.getBytes(): $ssid")
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
            LogUtils.diag(TAG, "Got SSID via getSsid(): $ssid")
            ssid
        } catch (e: Exception) {
            LogUtils.w(TAG, "getSsid() failed: ${e.message}")
            ""
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
            
            // Use Runtime to run command - when Shizuku is active, this has elevated perms
            // This is a fallback; the AIDL approach should work better
            val processBuilder = ProcessBuilder("cmd", "wifi", "get-softap-config")
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            val exitCode = process.waitFor()
            
            LogUtils.diag(TAG, "Shell output (exit=$exitCode): $output")
            
            if (exitCode != 0 || output.isBlank()) {
                LogUtils.w(TAG, "Shell command failed or returned empty")
                return null
            }
            
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

    private fun getHotspotCredentialsLegacy(wifiManager: WifiManager): HotspotCredentials? {
        return try {
            val getWifiApConfigurationMethod = wifiManager.javaClass.getMethod("getWifiApConfiguration")
            val wifiConfig = getWifiApConfigurationMethod.invoke(wifiManager)

            if (wifiConfig != null) {
                val ssidField = wifiConfig.javaClass.getDeclaredField("SSID")
                ssidField.isAccessible = true
                val ssid = ssidField.get(wifiConfig) as? String ?: ""

                val pskField = wifiConfig.javaClass.getDeclaredField("preSharedKey")
                pskField.isAccessible = true
                val passphrase = pskField.get(wifiConfig) as? String ?: ""

                if (ssid.isNotEmpty()) {
                    HotspotCredentials(ssid, passphrase)
                } else {
                    null
                }
            } else {
                LogUtils.w(TAG, "WifiConfiguration is null")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to get WifiConfiguration: ${e.message}")
            null
        }
    }

    fun isHotspotEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

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
            state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING
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

        if (!startTetheringViaConnector() && !startTetheringViaShell()) {
            LogUtils.w(TAG, "Programmatic hotspot enable failed")
            return false
        }

        // Give framework a brief moment to transition AP state.
        Thread.sleep(700)
        val enabled = isHotspotEnabled()
        LogUtils.diag(TAG, "Hotspot enabled after connector start: $enabled")
        return enabled
    }

    /**
     * Get intent to open hotspot settings
     */
    fun getHotspotSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
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
            if (candidate.resolveActivity(packageManager) != null) {
                return candidate
            }
        }

        return Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Turn hotspot off
     */
    fun stopHotspot(): Boolean {
        LogUtils.diag(TAG, "stopHotspot() called")

        val enabled = isHotspotEnabled()
        if (!enabled) {
            LogUtils.diag(TAG, "Hotspot is already disabled")
            return true
        }

        if (!isShizukuReady()) {
            LogUtils.w(TAG, "Shizuku unavailable; cannot stop hotspot programmatically")
            return false
        }

        if (!stopTetheringViaConnector() && !stopTetheringViaShell()) {
            LogUtils.w(TAG, "Programmatic hotspot disable failed")
            return false
        }

        Thread.sleep(500)
        val stillEnabled = isHotspotEnabled()
        LogUtils.diag(TAG, "Hotspot state after connector stop: enabled=$stillEnabled")
        return !stillEnabled
    }

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun startTetheringViaShell(): Boolean {
        val commands = listOf(
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start", "wifi"),
            arrayOf("cmd", "connectivity", "tether", "start", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start", "--type", "wifi"),
            arrayOf("cmd", "connectivity", "tether", "start", "--type", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "start"),
            arrayOf("cmd", "connectivity", "tether", "start"),
            arrayOf("/system/bin/cmd", "tethering", "start", "wifi"),
            arrayOf("cmd", "tethering", "start", "wifi"),
            arrayOf("/system/bin/cmd", "tethering", "start", "--type", "wifi"),
            arrayOf("cmd", "tethering", "start", "--type", "wifi"),
            arrayOf("/system/bin/cmd", "wifi", "start-softap"),
            arrayOf("cmd", "wifi", "start-softap"),
            arrayOf("service", "call", "wifi", "47")
        )
        for (command in commands) {
            val result = runShizukuCommand(command)
            if (result.exitCode == 0) {
                LogUtils.diag(TAG, "Hotspot start command succeeded: ${command.joinToString(" ")}")
                return true
            }
        }
        return false
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
            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop", "wifi"),
            arrayOf("cmd", "connectivity", "tether", "stop", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop", "--type", "wifi"),
            arrayOf("cmd", "connectivity", "tether", "stop", "--type", "wifi"),
            arrayOf("/system/bin/cmd", "connectivity", "tether", "stop"),
            arrayOf("cmd", "connectivity", "tether", "stop"),
            arrayOf("/system/bin/cmd", "tethering", "stop", "wifi"),
            arrayOf("cmd", "tethering", "stop", "wifi"),
            arrayOf("/system/bin/cmd", "tethering", "stop", "--type", "wifi"),
            arrayOf("cmd", "tethering", "stop", "--type", "wifi"),
            arrayOf("/system/bin/cmd", "wifi", "stop-softap"),
            arrayOf("cmd", "wifi", "stop-softap"),
            arrayOf("service", "call", "wifi", "49")
        )
        for (command in commands) {
            val result = runShizukuCommand(command)
            if (result.exitCode == 0) {
                LogUtils.diag(TAG, "Hotspot stop command succeeded: ${command.joinToString(" ")}")
                return true
            }
        }
        return false
    }

    private fun stopTetheringViaConnector(): Boolean {
        return try {
            val connector = getTetheringConnector() ?: return false
            val methods = connector.javaClass.methods
                .filter { it.name == "stopTethering" }
                .sortedByDescending { scoreTetheringMethod(it) }

            for (method in methods) {
                val args = buildTetheringArgs(method, isStart = false) ?: continue
                runCatching {
                    method.invoke(connector, *args)
                    LogUtils.diag(TAG, "Connector stopTethering invoked")
                    return true
                }
            }

            LogUtils.w(TAG, "No supported stopTethering signature succeeded")
            false
        } catch (e: Exception) {
            LogUtils.e(TAG, "stopTetheringViaConnector failed: ${e.message}")
            false
        }
    }

    private fun scoreTetheringMethod(method: Method): Int {
        val params = method.parameterTypes
        return when {
            params.isNotEmpty() && params[0].name == "android.net.TetheringRequestParcel" -> 100
            params.isNotEmpty() && params[0] == Int::class.javaPrimitiveType -> 80
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
                param.name == "android.net.IIntResultListener" -> {
                    createNoOpBinderInterface(param)
                }
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

    private fun createNoOpBinderInterface(interfaceClass: Class<*>): Any? {
        if (!interfaceClass.isInterface) {
            return null
        }

        val binder = android.os.Binder()
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                "onResult" -> null
                "toString" -> "NoOp${interfaceClass.simpleName}"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        }

        return Proxy.newProxyInstance(
            interfaceClass.classLoader,
            arrayOf(interfaceClass),
            handler
        )
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

    private fun runShizukuCommand(command: Array<String>): CommandResult {
        return try {
            val process = try {
                val publicMethod = Shizuku::class.java.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                try {
                    publicMethod.invoke(null, command, null, null) as Process
                } catch (e: IllegalAccessException) {
                    publicMethod.isAccessible = true
                    publicMethod.invoke(null, command, null, null) as Process
                }
            } catch (_: NoSuchMethodException) {
                val binder = Shizuku.getBinder()
                if (binder == null) {
                    return CommandResult(-1, "", "Shizuku binder unavailable")
                }

                val stubClass = Class.forName($$"moe.shizuku.server.IShizukuService$Stub")
                val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                val service = asInterface.invoke(null, binder)
                    ?: return CommandResult(-1, "", "IShizukuService unavailable")

                val newProcessMethod = service.javaClass.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                val remoteProcess = newProcessMethod.invoke(service, command, null, null)
                    ?: return CommandResult(-1, "", "IRemoteProcess unavailable")

                val remoteProcessClass = Class.forName("moe.shizuku.server.IRemoteProcess")
                val processClass = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
                val ctor = processClass.getDeclaredConstructor(remoteProcessClass)
                ctor.isAccessible = true
                ctor.newInstance(remoteProcess) as Process
            }
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            CommandResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "unknown error")
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )
}
