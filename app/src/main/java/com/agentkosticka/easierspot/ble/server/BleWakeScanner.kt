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

/** Hardware-filtered listener for authenticated, previously paired wake requests. */
@SuppressLint("MissingPermission")
class BleWakeScanner(
    private val context: Context,
    private val routes: () -> Set<Int>,
    private val onRequest: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BleWakeScanner"
        private const val RAW_DEBOUNCE_MS = 2_000L
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null
    private val recentlySeen = linkedMapOf<Int, Long>()

    fun start() {
        if (callback != null || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val uuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val filters = routes().map { route ->
            ScanFilter.Builder().setServiceData(
                uuid,
                byteArrayOf(
                    BleConstants.PROTOCOL_VERSION,
                    BleConstants.MESSAGE_WAKE_REQUEST,
                    route.toByte()
                ),
                byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte())
            ).build()
        }
        if (filters.isEmpty()) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            // Several Nothing/Samsung controllers claim FIRST_MATCH offload support but suppress
            // subsequent service-data changes. ALL_MATCHES remains hardware-filtered and reliable.
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = result.scanRecord?.getServiceData(uuid) ?: return
                if (payload.size != BleDiscoveryProtocol.PAYLOAD_SIZE) return
                val now = System.currentTimeMillis()
                val hash = payload.contentHashCode()
                if (now - (recentlySeen[hash] ?: 0L) < RAW_DEBOUNCE_MS) return
                recentlySeen[hash] = now
                recentlySeen.entries.removeAll { now - it.value > 60_000L }
                onRequest(payload)
            }

            override fun onScanFailed(errorCode: Int) {
                LogUtils.w(TAG, "Wake-request receiver unavailable ($errorCode)")
            }
        }.also { scanner.startScan(filters, settings, it) }
        LogUtils.i(TAG, "Paired wake receiver active; offload=${adapter?.isOffloadedFilteringSupported}")
    }

    fun stop() {
        val current = callback ?: return
        callback = null
        recentlySeen.clear()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(current) }
    }
}
