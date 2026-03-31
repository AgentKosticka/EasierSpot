package com.agentkosticka.easierspot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.server.BleAdvertiser
import com.agentkosticka.easierspot.ble.server.GattServer
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.ui.server.ServerActivity
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BleHotspotService : Service() {
    internal enum class ApprovalDecision {
        REQUEST_APPROVAL,
        AUTO_DENY,
        AUTO_APPROVE
    }

    companion object {
        private const val TAG = "BleHotspotService"
        private const val NOTIFICATION_ID = 1
        private const val ENABLE_HOTSPOT_NOTIFICATION_ID = 2
        private const val APPROVAL_NOTIFICATION_ID = 3
        private const val SERVICE_CHANNEL_ID = "ble_hotspot_service"
        private const val ALERTS_CHANNEL_ID = "ble_hotspot_alerts"
        const val ACTION_START_SERVER = "com.agentkosticka.easierspot.START_SERVER"
        const val ACTION_STOP_SERVER = "com.agentkosticka.easierspot.STOP_SERVER"
        const val ACTION_APPROVE_CLIENT = "com.agentkosticka.easierspot.APPROVE_CLIENT"
        const val ACTION_DENY_CLIENT = "com.agentkosticka.easierspot.DENY_CLIENT"
        const val ACTION_SHOW_APPROVAL = "com.agentkosticka.easierspot.SHOW_APPROVAL"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CLIENT_ADDRESS = "client_address"
        const val EXTRA_CLIENT_DEVICE_ID = "client_device_id"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_APPROVAL_IS_REMEMBERED = "approval_is_remembered"
        const val EXTRA_APPROVAL_DISPLAY_ID = "approval_display_id"
        const val EXTRA_APPROVAL_DISPLAY_NAME = "approval_display_name"
        const val EXTRA_APPROVAL_NICKNAME = "approval_nickname"
        const val ACTION_RESHOW_NOTIFICATION = "com.agentkosticka.easierspot.RESHOW_NOTIFICATION"

        @Volatile
        var isServerRunning: Boolean = false
            private set
        private const val STATE_PREFS = "server_service_state"
        private const val KEY_RUNNING = "running"
        private val CLIENT_PREFIX_REGEX = Regex("(?i)^client[-_\\s]*")

        internal fun normalizeIdentityForDisplay(rawIdentity: String?): String {
            val trimmed = rawIdentity?.trim().orEmpty()
            if (trimmed.isEmpty()) return "Unknown"

            var normalized = trimmed
            while (true) {
                val next = CLIENT_PREFIX_REGEX.replace(normalized, "")
                    .trimStart('-', '_', ' ')
                    .trim()
                if (next == normalized) break
                normalized = next
            }

            return normalized.ifBlank { trimmed }
        }
    }

    private var bleAdvertiser: BleAdvertiser? = null
    private var gattServer: GattServer? = null
    private var hotspotManager: HotspotManager? = null
    private var currentDeviceId: String? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            LogUtils.e(TAG, "Service coroutine failed", throwable)
        }
    )
    private val binder = LocalBinder()
    private val database by lazy { AppDatabase.getDatabase(this) }

    inner class LocalBinder : Binder() {
        fun getService(): BleHotspotService = this@BleHotspotService
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            hotspotManager = HotspotManager(this)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

            when (intent?.action) {
                ACTION_START_SERVER -> {
                    val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: UUID.randomUUID().toString().take(4)
                    currentDeviceId = deviceId
                    LogUtils.i(TAG, "Starting BLE server with deviceId: $deviceId")
                    startBleServer(deviceId)
                    isServerRunning = true
                    persistServerState(true)
                }
                ACTION_STOP_SERVER -> {
                    stopBleServer()
                    isServerRunning = false
                    persistServerState(false)
                    stopSelf()
                }
                ACTION_APPROVE_CLIENT -> {
                    dismissApprovalNotification()
                    val clientAddress = intent.getStringExtra(EXTRA_CLIENT_ADDRESS)
                    if (clientAddress != null) {
                        val clientDeviceId = intent.getStringExtra(EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
                        val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: "Unknown Device"
                        approveClient(clientAddress, clientDeviceId, clientName)
                    }
                }
                ACTION_DENY_CLIENT -> {
                    dismissApprovalNotification()
                    val clientAddress = intent.getStringExtra(EXTRA_CLIENT_ADDRESS)
                    if (clientAddress != null) {
                        denyClient(clientAddress)
                    }
                }
                ACTION_SHOW_APPROVAL -> {
                    val clientAddress = intent.getStringExtra(EXTRA_CLIENT_ADDRESS) ?: ""
                    val deviceId = intent.getStringExtra(EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
                    val deviceName = intent.getStringExtra(EXTRA_CLIENT_NAME)
                    val isRememberedClient = intent.getBooleanExtra(EXTRA_APPROVAL_IS_REMEMBERED, false)
                    val displayId = intent.getStringExtra(EXTRA_APPROVAL_DISPLAY_ID)
                    val displayName = intent.getStringExtra(EXTRA_APPROVAL_DISPLAY_NAME)
                    val nickname = intent.getStringExtra(EXTRA_APPROVAL_NICKNAME)
                    showApprovalNotification(
                        clientAddress = clientAddress,
                        deviceId = deviceId,
                        deviceName = deviceName,
                        isRememberedClient = isRememberedClient,
                        displayId = displayId,
                        displayName = displayName,
                        nickname = nickname
                    )
                }
                ACTION_RESHOW_NOTIFICATION -> {
                    val notification = createNotification()
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in onStartCommand", e)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun startBleServer(deviceId: String) {
        try {
            if (bleAdvertiser == null) {
                bleAdvertiser = BleAdvertiser(this, deviceId)
                gattServer = GattServer(this, deviceId)
                
                // Set callback for new client connections
                gattServer?.setNewClientCallback { clientAddress, clientStableId ->
                    checkAndRequestApproval(clientAddress, clientStableId)
                }
            }
            
            bleAdvertiser?.startAdvertising()
            
            // Check for advertising errors after a short delay
            android.os.Handler(mainLooper).postDelayed({
                val error = bleAdvertiser?.advertisingError?.value
                val isAdvertising = bleAdvertiser?.isAdvertising?.value ?: false
                if (error != null) {
                    LogUtils.e(TAG, "Advertising error: $error")
                } else if (isAdvertising) {
                    LogUtils.i(TAG, "BLE advertising active")
                }
            }, 1000)
            
            gattServer?.startServer()
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error starting BLE server", e)
        }
    }

    private fun stopBleServer() {
        bleAdvertiser?.stopAdvertising()
        gattServer?.stopServer()
        bleAdvertiser = null
        gattServer = null
        isServerRunning = false
        persistServerState(false)
        dismissApprovalNotification()
    }

    private fun checkAndRequestApproval(clientAddress: String, clientStableId: String?) {
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val rememberedClient = if (!clientStableId.isNullOrBlank()) {
                dao.getServerById(clientStableId) ?: dao.getServerByAddress(clientAddress)
            } else {
                dao.getServerByAddress(clientAddress)
            }

            if (rememberedClient != null) {
                dao.insertServer(
                    rememberedClient.copy(
                        deviceAddress = clientAddress,
                        lastSeen = System.currentTimeMillis()
                    )
                )
            }

            when (decideApprovalDecision(rememberedClient)) {
                ApprovalDecision.REQUEST_APPROVAL -> {
                    if (rememberedClient == null) {
                        LogUtils.i(TAG, "New client $clientAddress requires approval")
                        val stableId = clientStableId ?: "Unknown"
                        dispatchApprovalRequest(
                            clientAddress = clientAddress,
                            deviceId = stableId,
                            deviceName = stableId,
                            isRememberedClient = false,
                            nickname = null
                        )
                    } else {
                        LogUtils.i(TAG, "Client $clientAddress requires approval")
                        dispatchApprovalRequest(
                            clientAddress = clientAddress,
                            deviceId = rememberedClient.deviceId,
                            deviceName = rememberedClient.deviceName,
                            isRememberedClient = true,
                            nickname = rememberedClient.nickname
                        )
                    }
                }
                ApprovalDecision.AUTO_DENY -> {
                    LogUtils.i(TAG, "Client $clientAddress denied by saved policy")
                    denyClient(clientAddress)
                }
                ApprovalDecision.AUTO_APPROVE -> {
                    val remembered = rememberedClient ?: return@launch
                    LogUtils.i(TAG, "Client $clientAddress auto-approved by saved policy")
                    activateHotspotAndSendCredentials(clientAddress, remembered.deviceId)
                }
            }
        }
    }

    internal fun decideApprovalDecision(rememberedClient: RememberedServer?): ApprovalDecision {
        if (rememberedClient == null) return ApprovalDecision.REQUEST_APPROVAL
        return when (rememberedClient.approvalPolicy) {
            RememberedServer.APPROVAL_POLICY_DENIED -> ApprovalDecision.AUTO_DENY
            RememberedServer.APPROVAL_POLICY_ASK -> ApprovalDecision.REQUEST_APPROVAL
            RememberedServer.APPROVAL_POLICY_APPROVED -> {
                if (rememberedClient.isApproved) {
                    ApprovalDecision.AUTO_APPROVE
                } else {
                    ApprovalDecision.REQUEST_APPROVAL
                }
            }
            else -> ApprovalDecision.REQUEST_APPROVAL
        }
    }

    internal fun mergeApprovalMetadata(
        existing: RememberedServer,
        clientAddress: String,
        clientName: String,
        approvedAt: Long
    ): RememberedServer {
        val fallbackName = "Client-${existing.deviceId}"
        val incomingName = clientName.takeUnless { it.isBlank() || it == "Unknown Device" }
        return existing.copy(
            deviceName = existing.deviceName.ifBlank {
                incomingName ?: fallbackName
            },
            deviceAddress = clientAddress,
            lastSeen = approvedAt,
            lastApprovedAt = approvedAt,
            isApproved = true
        )
    }

    private fun dispatchApprovalRequest(
        clientAddress: String,
        deviceId: String,
        deviceName: String?,
        isRememberedClient: Boolean,
        nickname: String?
    ) {
        val normalizedDisplayId = normalizeIdentityForDisplay(deviceId)
        val normalizedDisplayName = normalizeIdentityForDisplay(deviceName)
            .takeUnless { it == "Unknown" }
            ?: normalizedDisplayId

        val broadcastIntent = Intent(ACTION_SHOW_APPROVAL).apply {
            `package` = packageName
            putExtra(EXTRA_CLIENT_ADDRESS, clientAddress)
            putExtra(EXTRA_CLIENT_DEVICE_ID, deviceId)
            putExtra(EXTRA_CLIENT_NAME, deviceName ?: "Unknown Device")
            putExtra(EXTRA_APPROVAL_IS_REMEMBERED, isRememberedClient)
            putExtra(EXTRA_APPROVAL_DISPLAY_ID, normalizedDisplayId)
            putExtra(EXTRA_APPROVAL_DISPLAY_NAME, normalizedDisplayName)
            putExtra(EXTRA_APPROVAL_NICKNAME, nickname)
        }
        sendBroadcast(broadcastIntent)
        showApprovalNotification(
            clientAddress = clientAddress,
            deviceId = deviceId,
            deviceName = deviceName,
            isRememberedClient = isRememberedClient,
            displayId = normalizedDisplayId,
            displayName = normalizedDisplayName,
            nickname = nickname
        )
    }
    
    private suspend fun activateHotspotAndSendCredentials(clientAddress: String, clientDeviceId: String? = null) {
        val hotspotStarted = hotspotManager?.startHotspot() ?: false
        
        if (!hotspotStarted) {
            // Hotspot couldn't be started programmatically
            // Check if user has it enabled already
            val isEnabled = hotspotManager?.isHotspotEnabled() ?: false
            if (!isEnabled) {
                LogUtils.w(TAG, "Hotspot not enabled - prompting user")
                // Send notification to user to enable hotspot
                showEnableHotspotNotification()
                // Wait and check periodically
                var attempts = 0
                while (attempts < 30) { // Wait up to 30 seconds
                    delay(1000)
                    if (hotspotManager?.isHotspotEnabled() == true) {
                        sendCredentialsToClient(clientAddress, clientDeviceId)
                        return
                    }
                    attempts++
                }
                LogUtils.w(TAG, "Timeout waiting for hotspot enable")
                withContext(Dispatchers.Main) {
                    gattServer?.denyClient(clientAddress)
                }
                return
            }
        }
        
        delay(1000)
        
        sendCredentialsToClient(clientAddress, clientDeviceId)
    }
    
    private suspend fun sendCredentialsToClient(clientAddress: String, clientDeviceId: String? = null) {
        val credentials = hotspotManager?.getHotspotCredentials()
        LogUtils.i(TAG, "Credentials: ssid=${credentials?.ssid ?: "(null)"}")

        if (credentials != null && credentials.ssid.isNotEmpty()) {
            updateLastApprovedAt(clientAddress, clientDeviceId)
            withContext(Dispatchers.Main) {
                gattServer?.approveClient(clientAddress)
                // Small delay to ensure approval notification is sent first
                delay(100)
                gattServer?.sendHotspotCredentials(clientAddress, credentials)
            }
        } else {
            withContext(Dispatchers.Main) {
                LogUtils.w(TAG, "No hotspot credentials available; denying client")
                gattServer?.denyClient(clientAddress)
            }
        }
    }
    
    private fun showEnableHotspotNotification() {
        if (!canPostNotifications()) {
            openTetheringSettingsDirectly()
            return
        }

        val intent = hotspotManager?.getTetheringSettingsIntent()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("Enable Hotspot")
            .setContentText("Tap to enable your existing hotspot configuration for sharing")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ENABLE_HOTSPOT_NOTIFICATION_ID, notification)
    }

    private fun approveClient(clientAddress: String, clientDeviceId: String, clientName: String) {
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val existing = dao.getServerById(clientDeviceId) ?: dao.getServerByAddress(clientAddress)
            val approvedAt = System.currentTimeMillis()

            if (existing != null) {
                dao.insertServer(mergeApprovalMetadata(existing, clientAddress, clientName, approvedAt))
            } else {
                dao.insertServer(
                    RememberedServer(
                        deviceId = clientDeviceId,
                        deviceName = clientName.ifBlank { "Client-$clientDeviceId" },
                        deviceAddress = clientAddress,
                        lastSeen = approvedAt,
                        lastApprovedAt = approvedAt,
                        isApproved = true
                    )
                )
            }
            activateHotspotAndSendCredentials(clientAddress, clientDeviceId)
        }
    }

    private suspend fun updateLastApprovedAt(clientAddress: String, clientDeviceId: String?) {
        val dao = database.rememberedServerDao()
        val server = if (!clientDeviceId.isNullOrBlank()) {
            dao.getServerById(clientDeviceId) ?: dao.getServerByAddress(clientAddress)
        } else {
            dao.getServerByAddress(clientAddress)
        }
        server?.let {
            val approvedAt = System.currentTimeMillis()
            dao.insertServer(
                it.copy(
                    deviceAddress = clientAddress,
                    lastSeen = approvedAt,
                    lastApprovedAt = approvedAt,
                    isApproved = true
                )
            )
        }
    }

    private fun denyClient(clientAddress: String) {
        gattServer?.denyClient(clientAddress)
    }

    private fun showApprovalNotification(
        clientAddress: String,
        deviceId: String,
        deviceName: String?,
        isRememberedClient: Boolean = false,
        displayId: String? = null,
        displayName: String? = null,
        nickname: String? = null
    ) {
        if (!canPostNotifications()) {
            return
        }

        val normalizedDisplayId = displayId ?: normalizeIdentityForDisplay(deviceId)
        val normalizedDisplayName = displayName ?: normalizeIdentityForDisplay(deviceName)
            .takeUnless { it == "Unknown" }
            ?: normalizedDisplayId

        val intent = Intent(this, ServerActivity::class.java).apply {
            putExtra(EXTRA_CLIENT_ADDRESS, clientAddress)
            putExtra(EXTRA_CLIENT_DEVICE_ID, deviceId)
            putExtra(EXTRA_CLIENT_NAME, normalizedDisplayName)
            putExtra(EXTRA_APPROVAL_IS_REMEMBERED, isRememberedClient)
            putExtra(EXTRA_APPROVAL_DISPLAY_ID, normalizedDisplayId)
            putExtra(EXTRA_APPROVAL_DISPLAY_NAME, normalizedDisplayName)
            putExtra(EXTRA_APPROVAL_NICKNAME, nickname)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("New client approval required")
            .setContentText("Tap to approve hotspot sharing for $normalizedDisplayId")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(APPROVAL_NOTIFICATION_ID, notification)
    }

    private fun dismissApprovalNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
        stopBleServer()
    }

    private fun persistServerState(running: Boolean) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_RUNNING, running)
            }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "BLE Hotspot Service",
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = "Running BLE hotspot sharing"

        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            "Hotspot approvals and prompts",
            NotificationManager.IMPORTANCE_HIGH
        )
        alertsChannel.description = "Approval and hotspot enable prompts"

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannels(listOf(serviceChannel, alertsChannel))
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, ServerActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val reshowIntent = Intent(this, BleHotspotService::class.java).apply {
            action = ACTION_RESHOW_NOTIFICATION
        }
        val reshowPendingIntent = PendingIntent.getService(
            this, 2, reshowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Hotspot sharing active")
            .setContentText("Click here to open app")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setDeleteIntent(reshowPendingIntent)
            .build().apply {
                flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
            }
    }

    private fun canPostNotifications(): Boolean {
        val hasRuntimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasRuntimePermission && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun openTetheringSettingsDirectly() {
        val intent = hotspotManager?.getTetheringSettingsIntent() ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            LogUtils.w(TAG, "Unable to open tethering settings: ${e.message}")
        }
    }
}
