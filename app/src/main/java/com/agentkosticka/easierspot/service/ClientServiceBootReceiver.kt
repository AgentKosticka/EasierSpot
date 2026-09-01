package com.agentkosticka.easierspot.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Notification
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.client.BleDiscoveryRegistrar
import com.agentkosticka.easierspot.ble.client.PRESENCE_WINDOW_MS
import com.agentkosticka.easierspot.ble.client.TrustedServerProfile
import com.agentkosticka.easierspot.ble.client.TrustedServerStore
import com.agentkosticka.easierspot.ble.client.RecentBleAddressCache
import com.agentkosticka.easierspot.shared.SharedConnectivityBackends
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClientServiceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BleDiscoveryRegistrar.ACTION_SCAN_RESULT -> handleScanResults(context, intent)
            ACTION_RECONCILE_DISCOVERY -> reconcileAsync(context)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                reconcileAsync(context)
                BleHotspotService.restoreIfEnabled(context)
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> if (
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                BluetoothAdapter.STATE_ON
            ) {
                reconcileAsync(context)
            }
        }
    }

    private fun handleScanResults(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
                if (error != 0) {
                    if (shouldRetryDiscovery(context)) scheduleDiscoveryRetry(context)
                    return@launch
                }
                context.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)
                    .edit { putInt(KEY_RETRY_ATTEMPT, 0) }
                scanResults(intent).forEach { result ->
                    val payload = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
                        ?: return@forEach
                    val beacon = BleDiscoveryProtocol.parseServer(payload) ?: return@forEach
                    runCatching {
                        RecentBleAddressCache(context).record(beacon.token, result.device.address)
                    }
                    val observed = TrustedServerStore(context).recordPresenceAndShouldAlert(
                        beacon.token,
                        beacon.networkRevision,
                        beacon.advertisingSession,
                        System.currentTimeMillis(),
                        flags = beacon.flags
                    ) ?: return@forEach
                    SharedConnectivityBackends.current.onPresenceChanged(context)
                    if (observed.second) showNearbyNotification(context, observed.first, beacon.flags)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun reconcileAsync(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!BleDiscoveryRegistrar.reconcile(context) && shouldRetryDiscovery(context)) {
                    scheduleDiscoveryRetry(context)
                }
                SharedConnectivityBackends.current.reconcile(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun shouldRetryDiscovery(context: Context): Boolean =
        AppPreferences.isBackgroundDiscoveryEnabled(context) &&
            TrustedServerStore(context).all().any { it.alertsEnabled }

    private fun scheduleDiscoveryRetry(context: Context) {
        val prefs = context.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)
        val attempt = prefs.getInt(KEY_RETRY_ATTEMPT, 0).coerceIn(0, RETRY_DELAYS.lastIndex)
        prefs.edit {
            putInt(KEY_RETRY_ATTEMPT, (attempt + 1).coerceAtMost(RETRY_DELAYS.lastIndex))
        }
        val retryIntent = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, ClientServiceBootReceiver::class.java).setAction(ACTION_RECONCILE_DISCOVERY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RETRY_DELAYS[attempt],
            retryIntent
        )
    }

    @Suppress("DEPRECATION")
    private fun scanResults(intent: Intent): List<ScanResult> {
        val results: ArrayList<ScanResult>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            )
        } else {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }
        return results.orEmpty()
    }

    private fun showNearbyNotification(
        context: Context,
        profile: TrustedServerProfile,
        flags: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LOUD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Nearby shared Wi-Fi",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "A quiet one-tap prompt when a paired sharing phone appears"
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(null, null)
            }
        )
        val notificationId = profile.fingerprint.hashCode()
        val connectIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, NearbyConnectReceiver::class.java).apply {
                putExtra(NearbyConnectReceiver.EXTRA_TOKEN, profile.discoveryToken)
                putExtra(NearbyConnectReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ready = flags and BleConstants.FLAG_HOTSPOT_ACTIVE != 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setContentTitle(profile.label)
            .setContentText(if (ready) "EasierSpot hotspot ready" else "Available via EasierSpot")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (ready) "EasierSpot hotspot ready. Tap Connect to join automatically."
                    else "Available via EasierSpot. Tap Connect to turn on the remote hotspot and join automatically."
                )
            )
            .setContentIntent(connectIntent)
            .addAction(0, "Connect", connectIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(PRESENCE_WINDOW_MS + 5_000L)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_notification)
                    .setContentTitle("EasierSpot network nearby")
                    .setContentText("Unlock to connect")
                    .build()
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    companion object {
        private const val CHANNEL_ID = "nearby_wifi_alerts_v5_network_style"
        private const val LOUD_CHANNEL_ID = "nearby_wifi_alerts_v3_loud"
        private const val LEGACY_CHANNEL_ID = "nearby_wifi_alerts_v2"
        private const val ACTION_RECONCILE_DISCOVERY =
            "com.agentkosticka.easierspot.RECONCILE_DISCOVERY"
        private const val RETRY_PREFS = "ble_discovery_retry"
        private const val KEY_RETRY_ATTEMPT = "attempt"
        private val RETRY_DELAYS = longArrayOf(15_000L, 60_000L, 5 * 60_000L, 30 * 60_000L)
    }
}
