package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.util.LogUtils

/** Short paired-client solicitation; passive server beacons remain the primary discovery path. */
@SuppressLint("MissingPermission")
class BleWakeAdvertiser(private val context: Context) {
    companion object { private const val TAG = "BleWakeAdvertiser" }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AdvertiseCallback? = null

    fun request(payload: ByteArray): Boolean {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) !=
            PackageManager.PERMISSION_GRANTED || adapter?.isMultipleAdvertisementSupported != true
        ) return false
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                LogUtils.i(TAG, "Authenticated wake burst transmitting")
            }

            override fun onStartFailure(errorCode: Int) {
                LogUtils.w(TAG, "Paired wake request unavailable ($errorCode)")
                callback = null
            }
        }.also { advertiser.startAdvertising(settings, data, it) }
        handler.postDelayed(::stop, BleConstants.WAKE_REQUEST_BURST_MS)
        return true
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val current = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(current) }
    }
}
