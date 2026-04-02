package com.agentkosticka.easierspot.ui.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.update.UpdateChecker
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Collects diagnostic information for various app components.
 */
object DiagnosticsInfoCollector {
    private const val TAG = "DiagnosticsInfoCollector"

    data class BleStatusInfo(
        val adapterEnabled: Boolean,
        val adapterState: String,
        val scanPermissionGranted: Boolean,
        val connectPermissionGranted: Boolean,
        val advertisePermissionGranted: Boolean,
        val advertisingSupported: Boolean,
        val error: String?
    )

    data class ShizukuStatusInfo(
        val isRunning: Boolean,
        val permissionGranted: Boolean,
        val versionInfo: String?,
        val troubleshootingTip: String
    )

    data class HotspotStatusInfo(
        val ssid: String?,
        val isEnabled: Boolean,
        val configSource: String,
        val credentialsAvailable: Boolean,
        val error: String?
    )

    data class DatabaseInfo(
        val totalDevices: Int,
        val approvedCount: Int,
        val askCount: Int,
        val deniedCount: Int,
        val databaseVersion: Int
    )

    data class AppInfo(
        val versionName: String,
        val versionCode: Long,
        val latestVersion: String?,
        val updateAvailable: Boolean,
        val lastUpdateCheck: String
    )

    data class SystemInfo(
        val androidVersion: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val availableMemoryMb: Long,
        val availableStorageGb: Long
    )

    /**
     * Collect BLE status information.
     */
    fun collectBleStatus(context: Context): BleStatusInfo {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: return BleStatusInfo(
                adapterEnabled = false,
                adapterState = "Not available",
                scanPermissionGranted = false,
                connectPermissionGranted = false,
                advertisePermissionGranted = false,
                advertisingSupported = false,
                error = "Bluetooth adapter not found"
            )

            val adapterEnabled = adapter.isEnabled
            val adapterState = when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> "Off"
                BluetoothAdapter.STATE_ON -> "On"
                BluetoothAdapter.STATE_TURNING_OFF -> "Turning off"
                BluetoothAdapter.STATE_TURNING_ON -> "Turning on"
                else -> "Unknown"
            }

            val scanPermissionGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val connectPermissionGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            val advertisePermissionGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED

            val advertisingSupported = adapter.isMultipleAdvertisementSupported

            BleStatusInfo(
                adapterEnabled = adapterEnabled,
                adapterState = adapterState,
                scanPermissionGranted = scanPermissionGranted,
                connectPermissionGranted = connectPermissionGranted,
                advertisePermissionGranted = advertisePermissionGranted,
                advertisingSupported = advertisingSupported,
                error = null
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error collecting BLE status", e)
            BleStatusInfo(
                adapterEnabled = false,
                adapterState = "Error",
                scanPermissionGranted = false,
                connectPermissionGranted = false,
                advertisePermissionGranted = false,
                advertisingSupported = false,
                error = e.message
            )
        }
    }

    /**
     * Collect Shizuku status information.
     */
    fun collectShizukuStatus(context: Context): ShizukuStatusInfo {
        return try {
            val isRunning = Shizuku.pingBinder()
            val permissionGranted = if (isRunning) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }

            val versionInfo = if (isRunning) {
                try {
                    "API ${Shizuku.getVersion()}"
                } catch (_: Exception) {
                    "Unknown version"
                }
            } else {
                null
            }

            val troubleshootingTip = when {
                !isRunning -> "Shizuku is not running. Please install and start Shizuku app."
                !permissionGranted -> "Permission denied. Open Shizuku and grant permission to EasierSpot."
                else -> "Shizuku is working correctly."
            }

            ShizukuStatusInfo(
                isRunning = isRunning,
                permissionGranted = permissionGranted,
                versionInfo = versionInfo,
                troubleshootingTip = troubleshootingTip
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error collecting Shizuku status", e)
            ShizukuStatusInfo(
                isRunning = false,
                permissionGranted = false,
                versionInfo = null,
                troubleshootingTip = "Error checking Shizuku: ${e.message}"
            )
        }
    }

    /**
     * Collect hotspot status information.
     */
    suspend fun collectHotspotStatus(context: Context): HotspotStatusInfo {
        return withContext(Dispatchers.IO) {
            try {
                val hotspotManager = HotspotManager(context)
                val credentials = hotspotManager.getHotspotCredentials()

                HotspotStatusInfo(
                    ssid = credentials?.ssid,
                    isEnabled = credentials != null,
                    configSource = if (Shizuku.pingBinder()) "Shizuku/API" else "N/A",
                    credentialsAvailable = credentials != null,
                    error = if (credentials == null) "Unable to retrieve hotspot credentials" else null
                )
            } catch (e: Exception) {
                LogUtils.e(TAG, "Error collecting hotspot status", e)
                HotspotStatusInfo(
                    ssid = null,
                    isEnabled = false,
                    configSource = "Error",
                    credentialsAvailable = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Collect database information.
     */
    suspend fun collectDatabaseInfo(context: Context): DatabaseInfo {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val dao = db.rememberedServerDao()

                val totalDevices = dao.getTotalCount()
                val approvedCount = dao.getCountByPolicy(RememberedServer.APPROVAL_POLICY_APPROVED)
                val askCount = dao.getCountByPolicy(RememberedServer.APPROVAL_POLICY_ASK)
                val deniedCount = dao.getCountByPolicy(RememberedServer.APPROVAL_POLICY_DENIED)

                DatabaseInfo(
                    totalDevices = totalDevices,
                    approvedCount = approvedCount,
                    askCount = askCount,
                    deniedCount = deniedCount,
                    databaseVersion = 3 // Current database version from AppDatabase
                )
            } catch (e: Exception) {
                LogUtils.e(TAG, "Error collecting database info", e)
                DatabaseInfo(
                    totalDevices = 0,
                    approvedCount = 0,
                    askCount = 0,
                    deniedCount = 0,
                    databaseVersion = 0
                )
            }
        }
    }

    /**
     * Collect app information.
     */
    fun collectAppInfo(context: Context): AppInfo {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = packageInfo.longVersionCode

            val updateState = UpdateChecker.getState(context)
            val lastCheckTime = if (updateState.checkedAtEpochMs > 0) {
                val diff = System.currentTimeMillis() - updateState.checkedAtEpochMs
                when {
                    diff < 60_000 -> "Just now"
                    diff < 3600_000 -> "${diff / 60_000}m ago"
                    diff < 86400_000 -> "${diff / 3600_000}h ago"
                    else -> "${diff / 86400_000}d ago"
                }
            } else {
                "Never"
            }

            AppInfo(
                versionName = versionName,
                versionCode = versionCode,
                latestVersion = updateState.latestVersionName,
                updateAvailable = updateState.updateAvailable,
                lastUpdateCheck = lastCheckTime
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error collecting app info", e)
            AppInfo(
                versionName = "Unknown",
                versionCode = 0,
                latestVersion = null,
                updateAvailable = false,
                lastUpdateCheck = "Error"
            )
        }
    }

    /**
     * Collect system information.
     */
    fun collectSystemInfo(context: Context): SystemInfo {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val availableMemoryMb = memoryInfo.availMem / (1024 * 1024)

            val statFs = StatFs(Environment.getDataDirectory().path)
            val availableStorageGb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024 * 1024 * 1024)

            SystemInfo(
                androidVersion = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                availableMemoryMb = availableMemoryMb,
                availableStorageGb = availableStorageGb
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error collecting system info", e)
            SystemInfo(
                androidVersion = "Unknown",
                sdkInt = 0,
                manufacturer = "Unknown",
                model = "Unknown",
                availableMemoryMb = 0,
                availableStorageGb = 0
            )
        }
    }
}
