package com.agentkosticka.easierspot.ble.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.service.ClientServiceBootReceiver
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.util.LogUtils

/** Registers an OS-owned BLE scan which can deliver results while our process is dead. */
object BleDiscoveryRegistrar {
    private const val TAG = "BleDiscoveryRegistrar"
    const val ACTION_SCAN_RESULT = "com.agentkosticka.easierspot.BACKGROUND_SCAN_RESULT"

    @SuppressLint("MissingPermission")
    fun reconcile(context: Context): Boolean {
        val app = context.applicationContext
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val pendingIntent = scanPendingIntent(app)
        val shouldRun = AppPreferences.isBackgroundDiscoveryEnabled(app) &&
            TrustedServerStore(app).all().any { it.alertsEnabled } &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED && adapter.isEnabled
        if (!shouldRun) {
            runCatching { scanner.stopScan(pendingIntent) }
            return false
        }
        val filter = ScanFilter.Builder().setServiceData(
            ParcelUuid(BleConstants.SERVICE_UUID),
            byteArrayOf(BleConstants.PROTOCOL_VERSION),
            byteArrayOf(0xff.toByte())
        ).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Do not batch paired-server arrivals. The server already pays for a short
            // high-power startup burst; delaying delivery here makes the user's explicit
            // "start sharing" action feel broken even though the radio saw it immediately.
            .setReportDelay(0L)
            .build()
        // PendingIntent scan registrations can survive process death and package replacement.
        // Replace the old registration explicitly so updated latency/filter settings take effect.
        runCatching { scanner.stopScan(pendingIntent) }
        val result = runCatching { scanner.startScan(listOf(filter), settings, pendingIntent) }
            .getOrElse {
                LogUtils.w(TAG, "Could not register OS-backed BLE discovery", it)
                return false
            }
        LogUtils.i(TAG, "OS-backed BLE discovery registration result=$result")
        return result == 0
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        val app = context.applicationContext
        val scanner = app.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanPendingIntent(app)) }
    }

    private fun scanPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        100,
        Intent(context, ClientServiceBootReceiver::class.java).setAction(ACTION_SCAN_RESULT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}
