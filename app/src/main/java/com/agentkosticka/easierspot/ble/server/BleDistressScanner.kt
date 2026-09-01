package com.agentkosticka.easierspot.ble.server

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.util.LogUtils

/** Always-filtered low-power receiver for authenticated disconnect bursts. */
@SuppressLint("MissingPermission")
class BleDistressScanner(private val context: Context, private val onPayload: (ByteArray) -> Unit) {
    companion object { private const val TAG = "BleDistressScanner" }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null

    fun start() {
        if (callback != null || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val filter = ScanFilter.Builder().setServiceData(
            serviceUuid,
            byteArrayOf(BleConstants.PROTOCOL_VERSION, BleConstants.MESSAGE_DISTRESS),
            byteArrayOf(0xff.toByte(), 0xff.toByte())
        ).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = result.scanRecord?.getServiceData(serviceUuid) ?: return
                if (payload.size == BleDiscoveryProtocol.PAYLOAD_SIZE) onPayload(payload)
            }

            override fun onScanFailed(errorCode: Int) {
                LogUtils.w(TAG, "Distress receiver unavailable ($errorCode); heartbeat expiry remains active")
            }
        }.also { scanner.startScan(listOf(filter), settings, it) }
        LogUtils.i(TAG, "Low-power filtered distress receiver active; offload=${adapter?.isOffloadedFilteringSupported}")
    }

    fun stop() {
        val current = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(current) }
    }
}
