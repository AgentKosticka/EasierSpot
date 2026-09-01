package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ScannerState {
    data object Idle : ScannerState
    data object Starting : ScannerState
    data object Active : ScannerState
    data object RecoveryScan : ScannerState
    data class Blocked(val reason: BlockReason) : ScannerState
    data class Failed(val message: String) : ScannerState

    enum class BlockReason { PERMISSION_MISSING, BLUETOOTH_OFF, ADAPTER_UNAVAILABLE }
}

/**
 * Reliable paired-server discovery for a foreground service.
 *
 * User-initiated scan: low-latency and unbatched for five seconds, then filtered balanced mode.
 * There are no periodic recovery scans while idle.
 */
@SuppressLint("MissingPermission")
class BackgroundBleScanner(
    private val context: Context,
    private val onServer: (DiscoveredServer) -> Unit
) {
    companion object {
        private const val TAG = "BackgroundBleScanner"
        private const val FAST_WINDOW_MS = 5_000L
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val handler = Handler(Looper.getMainLooper())
    private val serviceUuid = ParcelUuid(BleConstants.SERVICE_UUID)
    private var filteredCallback: ScanCallback? = null
    private var fastPhase = true
    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    private val balancedRunnable = Runnable { restartBalanced() }

    fun start(): Boolean {
        if (filteredCallback != null) return true
        _state.value = ScannerState.Starting
        if (!hasPermissions()) {
            _state.value = ScannerState.Blocked(ScannerState.BlockReason.PERMISSION_MISSING)
            return false
        }
        if (adapter == null) {
            _state.value = ScannerState.Blocked(ScannerState.BlockReason.ADAPTER_UNAVAILABLE)
            return false
        }
        if (!adapter.isEnabled) {
            _state.value = ScannerState.Blocked(ScannerState.BlockReason.BLUETOOTH_OFF)
            return false
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = ScannerState.Blocked(ScannerState.BlockReason.ADAPTER_UNAVAILABLE)
            return false
        }
        fastPhase = true
        val filter = ScanFilter.Builder().setServiceData(
            serviceUuid,
            byteArrayOf(BleConstants.PROTOCOL_VERSION),
            byteArrayOf(0xff.toByte())
        ).build()
        val callback = callback(filtered = true)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()

        filteredCallback = callback
        return runCatching {
            scanner.startScan(listOf(filter), settings, callback)
            handler.removeCallbacks(balancedRunnable)
            handler.postDelayed(balancedRunnable, FAST_WINDOW_MS)
            LogUtils.i(
                TAG,
                "Background scan active; filterOffload=${adapter?.isOffloadedFilteringSupported}, " +
                    "mode=low-latency"
            )
            _state.value = ScannerState.Active
            true
        }.getOrElse {
            filteredCallback = null
            LogUtils.w(TAG, "Could not start filtered background scan", it)
            _state.value = ScannerState.Failed(it.message ?: "Android rejected the BLE scan")
            false
        }
    }

    fun stop() {
        handler.removeCallbacks(balancedRunnable)
        val scanner = adapter?.bluetoothLeScanner
        filteredCallback?.let { runCatching { scanner?.stopScan(it) } }
        filteredCallback = null
        _state.value = ScannerState.Idle
    }

    private fun callback(filtered: Boolean) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = publish(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::publish)
        }

        override fun onScanFailed(errorCode: Int) {
            LogUtils.w(TAG, "${if (filtered) "Filtered" else "Recovery"} BLE scan failed ($errorCode)")
            if (filtered) {
                filteredCallback = null
                handler.removeCallbacks(balancedRunnable)
                _state.value = ScannerState.Failed("Android BLE scan failed ($errorCode)")
            }
        }
    }

    private fun restartBalanced() {
        if (!fastPhase || !hasPermissions()) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        filteredCallback?.let { runCatching { scanner.stopScan(it) } }
        val filter = ScanFilter.Builder().setServiceData(
            serviceUuid,
            byteArrayOf(BleConstants.PROTOCOL_VERSION),
            byteArrayOf(0xff.toByte())
        ).build()
        val callback = callback(filtered = true)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        fastPhase = false
        filteredCallback = callback
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure {
                filteredCallback = null
                LogUtils.w(TAG, "Could not degrade active scan to balanced mode", it)
            }
    }

    private fun publish(result: ScanResult) {
        val payload = result.scanRecord?.getServiceData(serviceUuid) ?: return
        val beacon = BleDiscoveryProtocol.parseServer(payload) ?: return
        runCatching { RecentBleAddressCache(context).record(beacon.token, result.device.address) }
        val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
        onServer(
            DiscoveredServer(
                beacon.token,
                name,
                result.rssi,
                result.device,
                beacon.networkRevision,
                beacon.flags
            )
        )
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}
