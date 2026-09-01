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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns one advertiser and automatically drops from a short discovery burst to low power. */
@SuppressLint("MissingPermission")
class BleAdvertiser(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") deviceId: String,
    private var networkRevision: Int = 0
) {
    companion object {
        private const val TAG = "BleAdvertiser"
        private const val SESSION_PREFS = "ble_advertising_session"
        private const val KEY_SESSION = "session"

        @Synchronized
        private fun nextAdvertisingSession(context: Context): Int {
            val preferences = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            val next = (preferences.getInt(KEY_SESSION, 0) + 1) and 0xff
            preferences.edit { putInt(KEY_SESSION, next) }
            return next
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private val discoveryToken by lazy { BleDiscoveryProtocol.serverToken(context) }
    private val advertisingSession = nextAdvertisingSession(context)
    private var hotspotActive = false
    private var hotspotStarting = false

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _advertisingError = MutableStateFlow<String?>(null)
    val advertisingError: StateFlow<String?> = _advertisingError.asStateFlow()

    private val downgradeRunnable = Runnable {
        if (advertiser != null) {
            LogUtils.i(TAG, "Startup burst complete; switching to configured steady advertising")
            if (_isAdvertising.value) stopCurrentAdvertisement()
            startWithMode(
                mode = sustainedMode(),
                txPower = sustainedTxPower(),
                reportResult = false
            )
        }
    }

    fun startAdvertising(onResult: (Result<Unit>) -> Unit = {}) {
        if (!hasBluetoothPermissions()) {
            fail("Missing Bluetooth advertise permission", onResult)
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null) {
            fail("Bluetooth adapter not available", onResult)
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is disabled", onResult)
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            fail("BLE peripheral advertising is not supported", onResult)
            return
        }
        if (_isAdvertising.value || advertiseCallback != null) {
            onResult(Result.success(Unit))
            return
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            fail("BLE advertiser is unavailable", onResult)
            return
        }

        startResult = onResult
        startWithMode(
            mode = AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            txPower = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            reportResult = true
        )
    }

    private fun startWithMode(
        mode: Int,
        txPower: Int,
        reportResult: Boolean,
        includeDeviceName: Boolean = true
    ) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(txPower)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), discoveryPayload())
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeDeviceName)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (advertiseCallback !== this) return
                _isAdvertising.value = true
                _advertisingError.value = null
                LogUtils.i(TAG, "Advertising active (mode=${settingsInEffect.mode})")
                if (reportResult) {
                    startResult?.invoke(Result.success(Unit))
                    startResult = null
                    mainHandler.removeCallbacks(downgradeRunnable)
                    mainHandler.postDelayed(downgradeRunnable, BleConstants.STARTUP_BURST_MS)
                }
            }

            override fun onStartFailure(errorCode: Int) {
                if (advertiseCallback !== this) return
                advertiseCallback = null
                _isAdvertising.value = false
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeDeviceName) {
                    LogUtils.w(TAG, "Bluetooth name is too long for scan response; retrying without it")
                    startWithMode(mode, txPower, reportResult, includeDeviceName = false)
                    return
                }
                val message = "Advertising failed: ${errorName(errorCode)} ($errorCode)"
                _advertisingError.value = message
                LogUtils.e(TAG, message)
                if (reportResult) {
                    startResult?.invoke(Result.failure(IllegalStateException(message)))
                    startResult = null
                }
            }
        }
        advertiseCallback = callback
        try {
            advertiser?.startAdvertising(settings, data, scanResponse, callback)
        } catch (error: Exception) {
            advertiseCallback = null
            _isAdvertising.value = false
            _advertisingError.value = error.message ?: "Unable to start advertising"
            if (reportResult) {
                startResult?.invoke(Result.failure(error))
                startResult = null
            }
        }
    }

    fun stopAdvertising() {
        mainHandler.removeCallbacks(downgradeRunnable)
        startResult = null
        stopCurrentAdvertisement()
        advertiser = null
    }

    fun setHotspotActive(active: Boolean, revision: Int = networkRevision) {
        if (hotspotActive == active && networkRevision == revision && (!active || !hotspotStarting)) return
        hotspotActive = active
        if (active) hotspotStarting = false
        networkRevision = revision
        refreshPayload()
    }

    fun setHotspotStarting(starting: Boolean) {
        if (hotspotStarting == starting) return
        hotspotStarting = starting
        refreshPayload()
    }

    private fun refreshPayload() {
        if (_isAdvertising.value) {
            mainHandler.removeCallbacks(downgradeRunnable)
            stopCurrentAdvertisement()
            startWithMode(sustainedMode(), sustainedTxPower(), reportResult = false)
        }
    }

    fun boostForPairedWake() {
        if (!_isAdvertising.value) return
        LogUtils.i(TAG, "Temporarily boosting advertisement for authenticated paired wake")
        mainHandler.removeCallbacks(downgradeRunnable)
        stopCurrentAdvertisement()
        startWithMode(
            AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
            reportResult = false
        )
        mainHandler.postDelayed(downgradeRunnable, BleConstants.WAKE_BOOST_MS)
    }

    private fun stopCurrentAdvertisement() {
        val callback = advertiseCallback
        advertiseCallback = null
        if (callback != null) {
            runCatching { advertiser?.stopAdvertising(callback) }
                .onFailure { LogUtils.w(TAG, "Error stopping advertiser", it) }
        }
        _isAdvertising.value = false
    }

    private fun discoveryPayload(): ByteArray {
        val flags = BleConstants.FLAG_AUTOMATIC_ACTIVATION or
            (if (hotspotActive) BleConstants.FLAG_HOTSPOT_ACTIVE else 0) or
            (if (hotspotStarting) BleConstants.FLAG_HOTSPOT_STARTING else 0)
        return BleDiscoveryProtocol.encodeServer(
            discoveryToken,
            networkRevision,
            advertisingSession,
            flags
        )
    }

    private fun sustainedMode(): Int = when (AppPreferences.getBleAdvertisingInterval(context)) {
        AppPreferences.AdvertisingInterval.SLOW -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        AppPreferences.AdvertisingInterval.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
        AppPreferences.AdvertisingInterval.FREQUENT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    }

    private fun sustainedTxPower(): Int = when (AppPreferences.getBroadcastStrength(context)) {
        AppPreferences.BroadcastStrength.LOW -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
        AppPreferences.BroadcastStrength.MEDIUM -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        AppPreferences.BroadcastStrength.HIGH -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    }

    private fun fail(message: String, callback: (Result<Unit>) -> Unit) {
        _advertisingError.value = message
        _isAdvertising.value = false
        LogUtils.e(TAG, message)
        callback(Result.failure(IllegalStateException(message)))
    }

    private fun errorName(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "controller error"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "controller advertiser limit"
        else -> "unknown error"
    }

    private fun hasBluetoothPermissions(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_ADVERTISE
    ) == PackageManager.PERMISSION_GRANTED
}
