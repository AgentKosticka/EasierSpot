package com.agentkosticka.easierspot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
import com.agentkosticka.easierspot.ui.settings.AppPreferences
import com.agentkosticka.easierspot.util.LogUtils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BleHotspotService : Service() {
    enum class ServerState {
        STOPPED,
        STARTING,
        ADVERTISING,
        CLIENT_PENDING,
        HOTSPOT_STARTING,
        SHARING,
        PAUSED_FOR_AUDIO,
        DEGRADED
    }

    internal enum class ApprovalDecision {
        REQUEST_APPROVAL,
        AUTO_DENY,
        AUTO_APPROVE
    }

    companion object {
        private const val TAG = "BleHotspotService"
        private const val NOTIFICATION_ID = 1
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
        const val ACTION_PAUSE_SERVER = "com.agentkosticka.easierspot.PAUSE_SERVER"
        const val ACTION_RESUME_SERVER = "com.agentkosticka.easierspot.RESUME_SERVER"

        private val _serverState = MutableStateFlow(ServerState.STOPPED)
        val serverState: StateFlow<ServerState> = _serverState.asStateFlow()
        val isServerRunning: Boolean
            get() = _serverState.value != ServerState.STOPPED
        private const val STATE_PREFS = "server_service_state"
        private const val KEY_RUNNING = "running"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HOTSPOT_OWNED = "hotspot_owned"
        private const val HOTSPOT_SAFETY_TIMEOUT_MS = 30 * 60 * 1000L
        private val BLE_RETRY_DELAYS_MS = longArrayOf(1_000L, 5_000L, 30_000L)
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
    private var hotspotStartedByApp = false
    private var hotspotShutdownJob: Job? = null
    private var bleRetryJob: Job? = null
    private var bleRetryAttempt = 0
    private var pausedForAudio = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            cancelBleRetry()
                            bleAdvertiser?.stopAdvertising()
                            gattServer?.stopServer()
                            _serverState.value = ServerState.DEGRADED
                        }
                        BluetoothAdapter.STATE_ON -> if (desiredRunning() && !pausedForAudio) {
                            currentDeviceId?.let(::startBleServer)
                        }
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothProfile.EXTRA_STATE,
                        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                    )
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTING) {
                        pauseForAudioConnection()
                    } else if (pausedForAudio && (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                            state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                    ) {
                        resumeAfterAudioConnection()
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleHotspotService = this@BleHotspotService
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            hotspotManager = HotspotManager(this)
            hotspotStartedByApp = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HOTSPOT_OWNED, false)
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                }
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val restoredAction = intent?.action ?: if (desiredRunning()) ACTION_START_SERVER else null
            if (restoredAction == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val notification = createNotification()
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

            when (restoredAction) {
                ACTION_START_SERVER -> {
                    val prefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                    val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
                        ?: prefs.getString(KEY_DEVICE_ID, null)
                        ?: UUID.randomUUID().toString().take(8)
                    currentDeviceId = deviceId
                    LogUtils.i(TAG, "Starting BLE server with deviceId: $deviceId")
                    _serverState.value = ServerState.STARTING
                    persistServerState(true, deviceId)
                    startBleServer(deviceId)
                }
                ACTION_STOP_SERVER -> {
                    persistServerState(false)
                    stopServerAndSelf()
                }
                ACTION_APPROVE_CLIENT -> {
                    dismissApprovalNotification()
                    val clientAddress = intent?.getStringExtra(EXTRA_CLIENT_ADDRESS)
                    if (clientAddress != null) {
                        val clientDeviceId = intent?.getStringExtra(EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
                        val clientName = intent?.getStringExtra(EXTRA_CLIENT_NAME) ?: "Unknown Device"
                        approveClient(clientAddress, clientDeviceId, clientName)
                    }
                }
                ACTION_DENY_CLIENT -> {
                    dismissApprovalNotification()
                    val clientAddress = intent?.getStringExtra(EXTRA_CLIENT_ADDRESS)
                    if (clientAddress != null) {
                        denyClient(clientAddress)
                    }
                }
                ACTION_SHOW_APPROVAL -> {
                    val clientAddress = intent?.getStringExtra(EXTRA_CLIENT_ADDRESS) ?: ""
                    val deviceId = intent?.getStringExtra(EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
                    val deviceName = intent?.getStringExtra(EXTRA_CLIENT_NAME)
                    val isRememberedClient = intent?.getBooleanExtra(EXTRA_APPROVAL_IS_REMEMBERED, false) == true
                    val displayId = intent?.getStringExtra(EXTRA_APPROVAL_DISPLAY_ID)
                    val displayName = intent?.getStringExtra(EXTRA_APPROVAL_DISPLAY_NAME)
                    val nickname = intent?.getStringExtra(EXTRA_APPROVAL_NICKNAME)
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
                ACTION_PAUSE_SERVER -> {
                    pauseForAudioConnection()
                    android.os.Handler(mainLooper).postDelayed({
                        if (pausedForAudio && desiredRunning()) resumeAfterAudioConnection()
                    }, 30_000L)
                }
                ACTION_RESUME_SERVER -> resumeAfterAudioConnection()
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in onStartCommand", e)
        }

        return if (desiredRunning()) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun startBleServer(deviceId: String) {
        bleRetryJob?.cancel()
        bleRetryJob = null
        bleRetryAttempt = 0
        attemptStartBleServer(deviceId)
    }

    private fun attemptStartBleServer(deviceId: String) {
        try {
            if (pausedForAudio) return
            if (bleAdvertiser != null || gattServer != null) {
                stopBleTransport()
            }

            val server = GattServer(this, deviceId).also { gattServer = it }
            server.setNewClientCallback { clientAddress, clientStableId ->
                _serverState.value = ServerState.CLIENT_PENDING
                checkAndRequestApproval(clientAddress, clientStableId)
            }
            server.startServer { result ->
                android.os.Handler(mainLooper).post {
                    result.onSuccess {
                        val advertiser = BleAdvertiser(this, deviceId).also { bleAdvertiser = it }
                        advertiser.startAdvertising { advertiseResult ->
                            advertiseResult.onSuccess {
                                bleRetryAttempt = 0
                                _serverState.value = ServerState.ADVERTISING
                                updateServiceNotification()
                            }.onFailure { error ->
                                LogUtils.e(TAG, "BLE advertising failed", error)
                                _serverState.value = ServerState.DEGRADED
                                stopBleTransport()
                                updateServiceNotification()
                                scheduleBleRetry(deviceId)
                            }
                        }
                    }.onFailure { error ->
                        LogUtils.e(TAG, "GATT server startup failed", error)
                        _serverState.value = ServerState.DEGRADED
                        stopBleTransport()
                        updateServiceNotification()
                        scheduleBleRetry(deviceId)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error starting BLE server", e)
            _serverState.value = ServerState.DEGRADED
            stopBleTransport()
            scheduleBleRetry(deviceId)
        }
    }

    private fun scheduleBleRetry(deviceId: String) {
        if (!desiredRunning() || pausedForAudio || bleRetryJob?.isActive == true) return
        val delayMs = BLE_RETRY_DELAYS_MS[bleRetryAttempt.coerceAtMost(BLE_RETRY_DELAYS_MS.lastIndex)]
        bleRetryAttempt++
        bleRetryJob = serviceScope.launch {
            delay(delayMs)
            if (desiredRunning() && !pausedForAudio) {
                withContext(Dispatchers.Main) { attemptStartBleServer(deviceId) }
            }
        }
    }

    private fun stopBleServer() {
        cancelBleRetry()
        stopBleTransport()
        hotspotShutdownJob?.cancel()
        hotspotShutdownJob = null
        _serverState.value = ServerState.STOPPED
        dismissApprovalNotification()
    }

    private fun stopBleTransport() {
        bleAdvertiser?.stopAdvertising()
        gattServer?.stopServer()
        bleAdvertiser = null
        gattServer = null
    }

    private fun cancelBleRetry() {
        bleRetryJob?.cancel()
        bleRetryJob = null
        bleRetryAttempt = 0
    }

    private fun checkAndRequestApproval(clientAddress: String, clientStableId: String?) {
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val rememberedClient = if (!clientStableId.isNullOrBlank()) {
                // Protocol-v2 trust is bound to the authenticated public-key fingerprint. Never
                // inherit approval from a legacy/spoofable BLE address.
                dao.getServerById(clientStableId)
            } else {
                dao.getServerByAddress(clientAddress)
            }
            val defaultPolicy = AppPreferences.getDefaultApprovalPolicy(this@BleHotspotService)
            val resolvedClientId = resolveClientId(clientStableId, clientAddress)
            val pairingCode = gattServer?.getPairingCode(clientAddress)
            val resolvedClientName = pairingCode?.let { "Pairing code $it" }
                ?: resolveClientName(clientStableId, resolvedClientId)

            if (rememberedClient != null) {
                dao.insertServer(
                    rememberedClient.copy(
                        deviceAddress = clientAddress,
                        lastSeen = System.currentTimeMillis()
                    )
                )
            }

            when (decideApprovalDecision(rememberedClient, defaultPolicy)) {
                ApprovalDecision.REQUEST_APPROVAL -> {
                    if (rememberedClient == null) {
                        LogUtils.i(TAG, "New client $clientAddress requires approval")
                        dispatchApprovalRequest(
                            clientAddress = clientAddress,
                            deviceId = resolvedClientId,
                            deviceName = resolvedClientName,
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
                    if (rememberedClient == null) {
                        rememberAutoDecisionForNewClient(
                            dao = dao,
                            clientAddress = clientAddress,
                            clientDeviceId = resolvedClientId,
                            clientName = resolvedClientName,
                            policy = AppPreferences.ApprovalPolicy.DENY
                        )
                    }
                    LogUtils.i(TAG, "Client $clientAddress denied by saved policy")
                    denyClient(clientAddress)
                }
                ApprovalDecision.AUTO_APPROVE -> {
                    val approvedDeviceId = if (rememberedClient == null) {
                        rememberAutoDecisionForNewClient(
                            dao = dao,
                            clientAddress = clientAddress,
                            clientDeviceId = resolvedClientId,
                            clientName = resolvedClientName,
                            policy = AppPreferences.ApprovalPolicy.APPROVE
                        )
                        resolvedClientId
                    } else {
                        rememberedClient.deviceId
                    }
                    LogUtils.i(TAG, "Client $clientAddress auto-approved by policy")
                    activateHotspotAndSendCredentials(clientAddress, approvedDeviceId)
                }
            }
        }
    }

    internal fun decideApprovalDecision(
        rememberedClient: RememberedServer?,
        defaultPolicy: AppPreferences.ApprovalPolicy = AppPreferences.ApprovalPolicy.ASK
    ): ApprovalDecision {
        if (rememberedClient == null) {
            return when (defaultPolicy) {
                AppPreferences.ApprovalPolicy.ASK -> ApprovalDecision.REQUEST_APPROVAL
                AppPreferences.ApprovalPolicy.APPROVE -> ApprovalDecision.AUTO_APPROVE
                AppPreferences.ApprovalPolicy.DENY -> ApprovalDecision.AUTO_DENY
            }
        }
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

    internal fun mapDefaultPolicyToRememberedPolicy(policy: AppPreferences.ApprovalPolicy): String {
        return when (policy) {
            AppPreferences.ApprovalPolicy.ASK -> RememberedServer.APPROVAL_POLICY_ASK
            AppPreferences.ApprovalPolicy.APPROVE -> RememberedServer.APPROVAL_POLICY_APPROVED
            AppPreferences.ApprovalPolicy.DENY -> RememberedServer.APPROVAL_POLICY_DENIED
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

    private fun resolveClientId(clientStableId: String?, clientAddress: String): String {
        if (!clientStableId.isNullOrBlank()) {
            return clientStableId
        }
        return "addr-${clientAddress.filter { it.isLetterOrDigit() }.lowercase()}"
    }

    private fun resolveClientName(clientStableId: String?, resolvedClientId: String): String {
        if (!clientStableId.isNullOrBlank()) {
            return clientStableId
        }
        return "Client-$resolvedClientId"
    }

    private suspend fun rememberAutoDecisionForNewClient(
        dao: com.agentkosticka.easierspot.data.db.RememberedServerDao,
        clientAddress: String,
        clientDeviceId: String,
        clientName: String,
        policy: AppPreferences.ApprovalPolicy
    ) {
        val now = System.currentTimeMillis()
        val isApproved = policy == AppPreferences.ApprovalPolicy.APPROVE
        dao.insertServer(
            RememberedServer(
                deviceId = clientDeviceId,
                deviceName = clientName,
                deviceAddress = clientAddress,
                lastSeen = now,
                isApproved = isApproved,
                nickname = null,
                approvalPolicy = mapDefaultPolicyToRememberedPolicy(policy),
                lastApprovedAt = if (isApproved) now else 0L
            )
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
        _serverState.value = ServerState.HOTSPOT_STARTING
        updateServiceNotification()
        val wasEnabled = hotspotManager?.isHotspotEnabled() == true
        val startAccepted = wasEnabled || hotspotManager?.startHotspot() == true
        if (!startAccepted) {
            LogUtils.w(TAG, "Hotspot start request was rejected")
            withContext(Dispatchers.Main) { gattServer?.denyClient(clientAddress) }
            _serverState.value = ServerState.ADVERTISING
            updateServiceNotification()
            return
        }

        if (!wasEnabled) setHotspotOwned(true)
        var enabled = hotspotManager?.isHotspotEnabled() == true
        var checksRemaining = 30
        while (!enabled && checksRemaining-- > 0) {
            delay(1_000)
            enabled = hotspotManager?.isHotspotEnabled() == true
        }
        if (!enabled) {
            LogUtils.w(TAG, "Timed out waiting for confirmed hotspot state")
            withContext(Dispatchers.Main) { gattServer?.denyClient(clientAddress) }
            stopOwnedHotspot()
            _serverState.value = ServerState.ADVERTISING
            updateServiceNotification()
            return
        }
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
            _serverState.value = ServerState.SHARING
            updateServiceNotification()
            scheduleHotspotSafetyStop()
        } else {
            withContext(Dispatchers.Main) {
                LogUtils.w(TAG, "No hotspot credentials available; denying client")
                gattServer?.denyClient(clientAddress)
            }
        }
    }
    
    private fun approveClient(clientAddress: String, clientDeviceId: String, clientName: String) {
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val existing = dao.getServerById(clientDeviceId)
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
            dao.getServerById(clientDeviceId)
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

        val notificationBuilder = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("New client approval required")
            .setContentText("Tap to approve hotspot sharing for $normalizedDisplayId")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Apply sound and vibration preferences
        var defaults = 0
        if (AppPreferences.isNotificationSoundEnabled(this)) {
            defaults = defaults or NotificationCompat.DEFAULT_SOUND
        }
        if (AppPreferences.isNotificationVibrationEnabled(this)) {
            defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
        }
        if (defaults != 0) {
            notificationBuilder.setDefaults(defaults)
        }

        val notification = notificationBuilder.build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(APPROVAL_NOTIFICATION_ID, notification)
    }

    private fun dismissApprovalNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        stopBleServer()
        if (hotspotStartedByApp) {
            runCatching { hotspotManager?.stopHotspot() }
            setHotspotOwned(false)
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun persistServerState(running: Boolean, deviceId: String? = currentDeviceId) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_RUNNING, running)
                if (deviceId != null) putString(KEY_DEVICE_ID, deviceId)
            }
    }

    private fun desiredRunning(): Boolean = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
        .getBoolean(KEY_RUNNING, false)

    private fun stopServerAndSelf() {
        stopBleServer()
        serviceScope.launch {
            stopOwnedHotspot()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@BleHotspotService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun stopOwnedHotspot() {
        if (!hotspotStartedByApp) return
        val stopped = hotspotManager?.stopHotspot() == true
        LogUtils.i(TAG, "Owned hotspot stop requested; accepted=$stopped")
        setHotspotOwned(false)
        hotspotShutdownJob?.cancel()
        hotspotShutdownJob = null
    }

    private fun scheduleHotspotSafetyStop() {
        if (!hotspotStartedByApp) return
        hotspotShutdownJob?.cancel()
        hotspotShutdownJob = serviceScope.launch {
            delay(HOTSPOT_SAFETY_TIMEOUT_MS)
            LogUtils.i(TAG, "Stopping app-owned hotspot at safety timeout")
            stopOwnedHotspot()
            if (desiredRunning()) {
                _serverState.value = ServerState.ADVERTISING
                updateServiceNotification()
            }
        }
    }

    private fun pauseForAudioConnection() {
        if (!desiredRunning() || pausedForAudio) return
        pausedForAudio = true
        cancelBleRetry()
        stopBleTransport()
        _serverState.value = ServerState.PAUSED_FOR_AUDIO
        updateServiceNotification()
    }

    private fun resumeAfterAudioConnection() {
        if (!pausedForAudio) return
        pausedForAudio = false
        _serverState.value = ServerState.STARTING
        currentDeviceId?.let(::startBleServer)
    }

    private fun setHotspotOwned(owned: Boolean) {
        hotspotStartedByApp = owned
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_HOTSPOT_OWNED, owned)
        }
    }

    private fun updateServiceNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
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

        val stopIntent = Intent(this, BleHotspotService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(this, BleHotspotService::class.java).apply {
            action = if (pausedForAudio) ACTION_RESUME_SERVER else ACTION_PAUSE_SERVER
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            4,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = when (_serverState.value) {
            ServerState.STARTING -> "Starting Bluetooth sharing…"
            ServerState.ADVERTISING -> "Low-power discovery active"
            ServerState.CLIENT_PENDING -> "Client approval pending"
            ServerState.HOTSPOT_STARTING -> "Starting hotspot…"
            ServerState.SHARING -> "Hotspot credentials shared"
            ServerState.PAUSED_FOR_AUDIO -> "Paused for Bluetooth audio"
            ServerState.DEGRADED -> "Bluetooth sharing needs attention"
            ServerState.STOPPED -> "Stopping hotspot sharing…"
        }

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Hotspot sharing active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                if (pausedForAudio) "Resume" else "Pause 30s",
                pausePendingIntent
            )
            .addAction(0, "Stop", stopPendingIntent)
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

}
