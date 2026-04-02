package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Handler
import android.os.Looper

data class DiscoveredServer(
    val deviceId: String,
    val deviceName: String?,
    val rssi: Int,
    val bluetoothDevice: android.bluetooth.BluetoothDevice
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    companion object {
        private const val TAG = "BleScanner"
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val foundDevices = mutableMapOf<String, DiscoveredServer>()
    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun startScan(onResult: (DiscoveredServer) -> Unit = {}) {
        Log.d(TAG, "startScan() called")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            _scanError.value = "Missing Bluetooth permissions"
            return
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            _scanError.value = "Bluetooth not available on this device"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            _scanError.value = "Bluetooth is disabled"
            return
        }

        if (isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE scanner not available")
            _scanError.value = "BLE not available"
            return
        }

        foundDevices.clear()
        _discoveredServers.value = emptyList()
        _scanError.value = null

        Log.d(TAG, "Building scan filter for Service UUID: ${BleConstants.SERVICE_UUID}")
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                val deviceAddress = result.device.address
                val deviceName = result.device.name
                Log.d(TAG, "Found device: $deviceAddress, name: $deviceName, rssi: ${result.rssi}")
                
                val server = DiscoveredServer(
                    deviceId = deviceAddress,
                    deviceName = deviceName ?: "Unknown Device",
                    rssi = result.rssi,
                    bluetoothDevice = result.device
                )
                foundDevices[deviceAddress] = server
                _discoveredServers.value = foundDevices.values.toList()
                onResult(server)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                super.onBatchScanResults(results)
                Log.d(TAG, "Batch scan results: ${results.size} devices")
                results.forEach { result ->
                    val deviceAddress = result.device.address
                    val server = DiscoveredServer(
                        deviceId = deviceAddress,
                        deviceName = result.device.name ?: "Unknown Device",
                        rssi = result.rssi,
                        bluetoothDevice = result.device
                    )
                    foundDevices[deviceAddress] = server
                }
                _discoveredServers.value = foundDevices.values.toList()
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                val errorMsg = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    else -> "Unknown error"
                }
                Log.e(TAG, "Scan failed: $errorMsg (code: $errorCode)")
                _scanError.value = "Scan failed: $errorMsg"
                _isScanning.value = false
            }
        }

        Log.d(TAG, "Starting BLE scan...")
        try {
            scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback!!)
            _isScanning.value = true
            Log.d(TAG, "✓ Scan started successfully")
            
            // Schedule auto-stop based on scan timeout preference
            val scanTimeoutMs = AppPreferences.getScanTimeoutMs(context)
            scanTimeoutRunnable = Runnable {
                Log.d(TAG, "Scan timeout reached (${scanTimeoutMs}ms), stopping scan")
                stopScan()
            }
            scanTimeoutHandler.postDelayed(scanTimeoutRunnable!!, scanTimeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting scan", e)
            _scanError.value = "Exception: ${e.message}"
        }
    }

    fun stopScan() {
        Log.d(TAG, "stopScan() called")
        if (!isScanning.value && scanCallback == null) {
            Log.d(TAG, "Not scanning, nothing to stop")
            return
        }

        // Cancel any pending timeout
        scanTimeoutRunnable?.let {
            scanTimeoutHandler.removeCallbacks(it)
            scanTimeoutRunnable = null
        }

        try {
            scanCallback?.let {
                scanner?.stopScan(it)
                Log.d(TAG, "Scan stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
        scanCallback = null
        _isScanning.value = false
    }

    private fun hasBluetoothPermissions(): Boolean {
        val hasScan = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        Log.d(TAG, "Permissions - BLUETOOTH_SCAN: $hasScan, ACCESS_FINE_LOCATION: $hasLocation")
        return hasScan && hasLocation
    }
}
