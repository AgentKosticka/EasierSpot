package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredServer(
    val deviceId: String,
    val deviceName: String?,
    val rssi: Int,
    val bluetoothDevice: BluetoothDevice
)

/** Foreground, bounded scanner with a short fast phase and throttled result updates. */
@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    companion object {
        private const val TAG = "BleScanner"
        private const val RESULT_THROTTLE_MS = 1_000L
        private const val MIN_RSSI_CHANGE_DB = 4
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var activeMode = ScanSettings.SCAN_MODE_LOW_LATENCY

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val foundDevices = linkedMapOf<String, DiscoveredServer>()
    private val lastPublishedAt = mutableMapOf<String, Long>()
    private val stopRunnable = Runnable { stopScan() }
    private val balancedRunnable = Runnable {
        if (_isScanning.value && activeMode == ScanSettings.SCAN_MODE_LOW_LATENCY) {
            restartInMode(ScanSettings.SCAN_MODE_BALANCED)
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true
    fun isBluetoothAvailable(): Boolean = adapter != null

    fun startScan(onResult: (DiscoveredServer) -> Unit = {}) {
        if (!hasBluetoothPermissions()) {
            fail("Missing Bluetooth scan/connect permission")
            return
        }
        val currentAdapter = adapter
        if (currentAdapter == null) {
            fail("Bluetooth is not available on this device")
            return
        }
        if (!currentAdapter.isEnabled) {
            fail("Bluetooth is disabled")
            return
        }
        if (_isScanning.value) return

        scanner = currentAdapter.bluetoothLeScanner
        if (scanner == null) {
            fail("BLE scanner is unavailable")
            return
        }

        foundDevices.clear()
        lastPublishedAt.clear()
        _discoveredServers.value = emptyList()
        _scanError.value = null
        scanCallback = callback(onResult)
        activeMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        if (!startCurrentScan()) return

        _isScanning.value = true
        val timeout = AppPreferences.getScanTimeoutMs(context)
            .coerceIn(5_000L, 60_000L)
        handler.postDelayed(balancedRunnable, BleConstants.SCAN_FAST_PHASE_MS.coerceAtMost(timeout))
        handler.postDelayed(stopRunnable, timeout)
    }

    private fun restartInMode(mode: Int) {
        val callback = scanCallback ?: return
        runCatching { scanner?.stopScan(callback) }
        activeMode = mode
        if (!startCurrentScan()) stopScan()
    }

    private fun startCurrentScan(): Boolean {
        val callback = scanCallback ?: return false
        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val filter = ScanFilter.Builder()
            .setServiceData(
                serviceUuid,
                byteArrayOf(BleConstants.PROTOCOL_VERSION),
                byteArrayOf(0xFF.toByte())
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(activeMode)
            .setReportDelay(0)
            .build()
        return try {
            scanner?.startScan(listOf(filter), settings, callback)
            LogUtils.i(TAG, "BLE scan active (mode=$activeMode)")
            true
        } catch (error: Exception) {
            fail(error.message ?: "Unable to start BLE scan")
            false
        }
    }

    private fun callback(onResult: (DiscoveredServer) -> Unit) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            publish(result, onResult)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { publish(it, onResult) }
        }

        override fun onScanFailed(errorCode: Int) {
            val message = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Bluetooth scanner registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth controller scan error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Filtered BLE scanning is unsupported"
                else -> "BLE scan failed ($errorCode)"
            }
            fail(message)
            stopScan()
        }
    }

    private fun publish(result: ScanResult, onResult: (DiscoveredServer) -> Unit) {
        val payload = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID)) ?: return
        if (payload.size < 5 || payload[0] != BleConstants.PROTOCOL_VERSION) return
        val token = payload.copyOfRange(1, 5).joinToString("") { "%02x".format(it) }
        val previous = foundDevices[token]
        val now = System.currentTimeMillis()
        val last = lastPublishedAt[token] ?: 0L
        if (previous != null && now - last < RESULT_THROTTLE_MS &&
            kotlin.math.abs(previous.rssi - result.rssi) < MIN_RSSI_CHANGE_DB
        ) return

        val advertisedName = result.scanRecord?.deviceName
            ?: runCatching { result.device.name }.getOrNull()
        val server = DiscoveredServer(
            deviceId = token,
            deviceName = advertisedName ?: "EasierSpot ${token.takeLast(4).uppercase()}",
            rssi = result.rssi,
            bluetoothDevice = result.device
        )
        foundDevices[token] = server
        lastPublishedAt[token] = now
        _discoveredServers.value = foundDevices.values.sortedByDescending { it.rssi }
        onResult(server)
    }

    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        handler.removeCallbacks(balancedRunnable)
        val callback = scanCallback
        scanCallback = null
        if (callback != null) {
            runCatching { scanner?.stopScan(callback) }
                .onFailure { LogUtils.w(TAG, "Error stopping scan", it) }
        }
        scanner = null
        _isScanning.value = false
    }

    private fun fail(message: String) {
        _scanError.value = message
        LogUtils.e(TAG, message)
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
