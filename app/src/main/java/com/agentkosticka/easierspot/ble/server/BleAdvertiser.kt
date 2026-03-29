package com.agentkosticka.easierspot.ble.server

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BleAdvertiser(private val context: Context, private val deviceId: String) {
    companion object {
        private const val TAG = "BleAdvertiser"
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _advertisingError = MutableStateFlow<String?>(null)
    val advertisingError: StateFlow<String?> = _advertisingError.asStateFlow()

    fun startAdvertising() {
        Log.d(TAG, "startAdvertising() called, deviceId=$deviceId")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            _advertisingError.value = "Missing Bluetooth permissions"
            return
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            _advertisingError.value = "Bluetooth adapter not available"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled")
            _advertisingError.value = "Bluetooth is disabled"
            return
        }

        if (isAdvertising.value) {
            Log.d(TAG, "Already advertising")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device")
            _advertisingError.value = "BLE advertising not supported on this device"
            return
        }

        Log.d(TAG, "Building advertise settings...")
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .build()

        Log.d(TAG, "Building advertise data with Service UUID: ${BleConstants.SERVICE_UUID}")
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)  // Save space
            .setIncludeDeviceName(false)    // Save space, put in scan response
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addManufacturerData(0x00FF, encodeDeviceId(deviceId))  // Use generic manufacturer ID
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                Log.d(TAG, "✓ Advertising started successfully!")
                Log.d(TAG, "  Mode: ${settingsInEffect.mode}, TxPower: ${settingsInEffect.txPowerLevel}")
                _isAdvertising.value = true
                _advertisingError.value = null
            }

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                val errorMsg = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Already advertising"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large for advertisement"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Unknown error"
                }
                Log.e(TAG, "✗ Advertising failed: $errorMsg (code: $errorCode)")
                _isAdvertising.value = false
                _advertisingError.value = "Advertising failed: $errorMsg"
            }
        }

        Log.d(TAG, "Starting advertisement...")
        try {
            advertiser?.startAdvertising(advertiseSettings, advertiseData, scanResponseData, advertiseCallback!!)
            Log.d(TAG, "startAdvertising() call completed, waiting for callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising", e)
            _advertisingError.value = "Exception: ${e.message}"
        }
    }

    fun stopAdvertising() {
        Log.d(TAG, "stopAdvertising() called")
        if (!isAdvertising.value && advertiseCallback == null) {
            Log.d(TAG, "Not advertising, nothing to stop")
            return
        }

        try {
            advertiseCallback?.let {
                advertiser?.stopAdvertising(it)
                Log.d(TAG, "Advertising stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping advertising", e)
        }
        advertiseCallback = null
        _isAdvertising.value = false
    }

    private fun encodeDeviceId(deviceId: String): ByteArray {
        val bytes = deviceId.toByteArray(Charsets.UTF_8)
        return ByteArray(4) { bytes.getOrNull(it) ?: 0 }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    }
}
