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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.agentkosticka.easierspot.R
import com.agentkosticka.easierspot.ble.server.BleAdvertiser
import com.agentkosticka.easierspot.ble.server.BleDistressScanner
import com.agentkosticka.easierspot.ble.server.BleWakeScanner
import com.agentkosticka.easierspot.ble.server.GattServer
import com.agentkosticka.easierspot.ble.server.WakeBoostLimiter
import com.agentkosticka.easierspot.ble.server.WakePeerStore
import com.agentkosticka.easierspot.ble.BleConstants
import com.agentkosticka.easierspot.ble.BleDiscoveryProtocol
import com.agentkosticka.easierspot.ble.ServerStatusMessage
import com.agentkosticka.easierspot.data.db.AppDatabase
import com.agentkosticka.easierspot.data.model.RememberedServer
import com.agentkosticka.easierspot.hotspot.HotspotManager
import com.agentkosticka.easierspot.hotspot.HotspotActivationState
import com.agentkosticka.easierspot.control.UdpControlServer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import rikka.shizuku.Shizuku

class BleHotspotService : Service() {
    data class ConnectedClientSummary(
        val stableId: String,
        val label: String,
        val lastSeenAt: Long
    )

    enum class ServerState {
        STOPPED,
        STARTING,
        NEEDS_SHIZUKU,
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
        const val ACTION_DISCONNECT_CLIENT = "com.agentkosticka.easierspot.DISCONNECT_CLIENT"
        const val ACTION_SHOW_APPROVAL = "com.agentkosticka.easierspot.SHOW_APPROVAL"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_CLIENT_ADDRESS = "client_address"
        const val EXTRA_CLIENT_DEVICE_ID = "client_device_id"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_CLIENT_STABLE_ID = "client_stable_id"
        const val EXTRA_APPROVAL_IS_REMEMBERED = "approval_is_remembered"
        const val EXTRA_APPROVAL_DISPLAY_ID = "approval_display_id"
        const val EXTRA_APPROVAL_DISPLAY_NAME = "approval_display_name"
        const val EXTRA_APPROVAL_NICKNAME = "approval_nickname"
        const val ACTION_PAUSE_SERVER = "com.agentkosticka.easierspot.PAUSE_SERVER"
        const val ACTION_RESUME_SERVER = "com.agentkosticka.easierspot.RESUME_SERVER"
        const val ACTION_RESTORE_NOTIFICATION =
            "com.agentkosticka.easierspot.RESTORE_SERVER_NOTIFICATION"

        private val _serverState = MutableStateFlow(ServerState.STOPPED)
        val serverState: StateFlow<ServerState> = _serverState.asStateFlow()
        private val _connectedClients = MutableStateFlow<List<ConnectedClientSummary>>(emptyList())
        val connectedClients: StateFlow<List<ConnectedClientSummary>> = _connectedClients.asStateFlow()
        val isServerRunning: Boolean
            get() = _serverState.value != ServerState.STOPPED
        private const val STATE_PREFS = "server_service_state"
        private const val KEY_RUNNING = "running"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_HOTSPOT_OWNED = "hotspot_owned"
        private const val KEY_HOTSPOT_BOOT_COUNT = "hotspot_boot_count"
        private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
        private const val KEY_HOTSPOT_REVISION = "hotspot_revision"
        private const val KEY_HOTSPOT_STARTED_AT = "hotspot_started_at"
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLING = 10
        private const val WIFI_AP_STATE_DISABLED = 11
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

        internal fun shouldAutoStopHotspot(startedByApp: Boolean, activeClientCount: Int): Boolean =
            startedByApp && activeClientCount == 0

        fun restoreIfEnabled(context: Context) {
            val enabled = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RUNNING, false)
            if (!enabled) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleHotspotService::class.java).apply { action = ACTION_START_SERVER }
            )
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
    private var heartbeatMonitorJob: Job? = null
    private var distressScanner: BleDistressScanner? = null
    private var wakeScanner: BleWakeScanner? = null
    private val wakePeerStore by lazy { WakePeerStore(this) }
    private val udpControlServer by lazy {
        UdpControlServer(wakePeerStore) { fingerprint, type ->
            serviceScope.launch { onUdpControl(fingerprint, type) }
        }
    }
    private val wakeBoostLimiter = WakeBoostLimiter()
    private data class ActiveClient(var lastHeartbeatAt: Long)
    private val activeClients = ConcurrentHashMap<String, ActiveClient>()
    private var bleRetryJob: Job? = null
    private var wakeActivationJob: Job? = null
    private var wakeLeaseJob: Job? = null
    private var wakeScannerStartJob: Job? = null
    private val hotspotActivationMutex = Mutex()
    private var bleRetryAttempt = 0
    private var pausedForAudio = false
    @Volatile private var ownershipRestored = false
    @Volatile private var bleStartInFlight = false
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (desiredRunning() && !pausedForAudio) {
            restoreHotspotOwnershipAsync()
            currentDeviceId?.let { deviceId ->
                android.os.Handler(mainLooper).post { startBleServer(deviceId) }
            }
        }
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        if (desiredRunning()) {
            serviceScope.launch {
                val active = hotspotManager?.isHotspotEnabled() == true
                withContext(Dispatchers.Main) {
                    bleAdvertiser?.stopAdvertising()
                    wakeScanner?.stop()
                    gattServer?.updateServerStatus(
                        ServerStatusMessage(
                            ServerStatusMessage.Type.PRIVILEGED_CONTROL_LOST,
                            activeClients.size
                        )
                    )
                    bleStartInFlight = false
                    if (!active) stopBleTransport()
                    _serverState.value = if (active) ServerState.DEGRADED else ServerState.NEEDS_SHIZUKU
                    updateServiceNotification()
                }
            }
        }
    }
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
                ACTION_WIFI_AP_STATE_CHANGED -> {
                    val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                    if (state == WIFI_AP_STATE_DISABLING || state == WIFI_AP_STATE_DISABLED) {
                        gattServer?.updateServerStatus(
                            ServerStatusMessage(
                                ServerStatusMessage.Type.HOTSPOT_STOPPED,
                                activeClients.size
                            )
                        )
                        if (hotspotStartedByApp) {
                            LogUtils.i(TAG, "Hotspot was switched off outside EasierSpot; ownership released")
                            setHotspotOwned(false)
                        }
                        activeClients.clear()
                        publishConnectedClients()
                        udpControlServer.stop()
                        bleAdvertiser?.setHotspotActive(false)
                        if (desiredRunning() && !pausedForAudio) {
                            _serverState.value = ServerState.ADVERTISING
                            startWakeRequestReceiver()
                            updateServiceNotification()
                        }
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
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(ACTION_WIFI_AP_STATE_CHANGED)
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
            restoreHotspotOwnershipAsync()

            when (restoredAction) {
                ACTION_START_SERVER -> {
                    val prefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
                    val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
                        ?: prefs.getString(KEY_DEVICE_ID, null)
                        ?: UUID.randomUUID().toString().take(8)
                    currentDeviceId = deviceId
                    LogUtils.i(TAG, "Starting BLE server")
                    _serverState.value = ServerState.STARTING
                    persistServerState(true, deviceId)
                    startBleServer(deviceId)
                }
                ACTION_STOP_SERVER -> {
                    persistServerState(false)
                    stopServerAndSelf()
                }
                ACTION_DISCONNECT_CLIENT -> {
                    intent?.getStringExtra(EXTRA_CLIENT_STABLE_ID)?.let(::disconnectActiveClient)
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
                    if (clientAddress != null) denyClient(clientAddress)
                }
                ACTION_SHOW_APPROVAL -> {
                    val clientAddress = intent?.getStringExtra(EXTRA_CLIENT_ADDRESS) ?: ""
                    val deviceId = intent?.getStringExtra(EXTRA_CLIENT_DEVICE_ID) ?: "Unknown"
                    val deviceName = intent?.getStringExtra(EXTRA_CLIENT_NAME)
                    val isRememberedClient = intent?.getBooleanExtra(EXTRA_APPROVAL_IS_REMEMBERED, false) == true
                    val displayId = intent?.getStringExtra(EXTRA_APPROVAL_DISPLAY_ID)
                    val displayName = intent?.getStringExtra(EXTRA_APPROVAL_DISPLAY_NAME)
                    val nickname = intent?.getStringExtra(EXTRA_APPROVAL_NICKNAME)
                    showApprovalNotification(clientAddress, deviceId, deviceName, isRememberedClient, displayId, displayName, nickname)
                }
                ACTION_PAUSE_SERVER -> {
                    pauseForAudioConnection()
                    android.os.Handler(mainLooper).postDelayed({
                        if (pausedForAudio && desiredRunning()) resumeAfterAudioConnection()
                    }, 30_000L)
                }
                ACTION_RESUME_SERVER -> resumeAfterAudioConnection()
                ACTION_RESTORE_NOTIFICATION -> {
                    if (desiredRunning()) updateServiceNotification() else stopSelf(startId)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in onStartCommand", e)
        }
        return if (desiredRunning()) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun restoreHotspotOwnershipAsync() {
        if (ownershipRestored) return
        ownershipRestored = true
        serviceScope.launch {
            val manager = hotspotManager ?: return@launch
            if (!manager.isShizukuAvailable()) {
                ownershipRestored = false
                return@launch
            }
            val ownershipPrefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
            val currentBoot = currentBootCount()
            val recordedBoot = ownershipPrefs.getInt(KEY_HOTSPOT_BOOT_COUNT, -1)
            val activeCredentials = manager.getHotspotCredentials()
            val active = manager.isHotspotEnabled()
            val expectedSsid = ownershipPrefs.getString(KEY_HOTSPOT_SSID, null)
            val expectedRevision = ownershipPrefs.getInt(KEY_HOTSPOT_REVISION, -1)
            val configurationMatches = activeCredentials != null && expectedSsid != null &&
                activeCredentials.ssid == expectedSsid &&
                BleDiscoveryProtocol.networkRevision(activeCredentials) == expectedRevision
            val restored = ownershipPrefs.getBoolean(KEY_HOTSPOT_OWNED, false) &&
                currentBoot >= 0 && currentBoot == recordedBoot && active && configurationMatches
            withContext(Dispatchers.Main) {
                hotspotStartedByApp = restored
                if (!restored) setHotspotOwned(false)
                if (active) udpControlServer.start(serviceScope)
            }
        }
    }

    private fun startBleServer(deviceId: String) {
        bleRetryJob?.cancel()
        bleRetryJob = null
        bleRetryAttempt = 0
        attemptStartBleServer(deviceId)
    }

    private fun attemptStartBleServer(deviceId: String) {
        try {
            if (pausedForAudio || bleStartInFlight) return
            if ((_serverState.value == ServerState.ADVERTISING || _serverState.value == ServerState.SHARING) &&
                bleAdvertiser != null && gattServer != null) return
            if (hotspotManager?.isShizukuAvailable() != true) {
                _serverState.value = ServerState.NEEDS_SHIZUKU
                updateServiceNotification()
                scheduleBleRetry(deviceId)
                return
            }
            bleStartInFlight = true
            if (bleAdvertiser != null || gattServer != null) stopBleTransport()
            val server = GattServer(this, deviceId).also { gattServer = it }
            server.setNewClientCallback { clientAddress, clientStableId ->
                _serverState.value = ServerState.CLIENT_PENDING
                checkAndRequestApproval(clientAddress, clientStableId)
            }
            server.setClientConnectionStateCallback { address, connected, stableId ->
                LogUtils.i(TAG, "Authenticated client link $address connected=$connected id=$stableId")
            }
            server.setSessionControlCallback { stableId, signal ->
                when (signal) {
                    BleConstants.CONTROL_HEARTBEAT -> activeClients[stableId]?.lastHeartbeatAt = System.currentTimeMillis()
                    BleConstants.CONTROL_GOODBYE -> removeActiveClient(stableId, "authenticated goodbye")
                }
            }
            server.startServer { result ->
                android.os.Handler(mainLooper).post {
                    result.onSuccess {
                        serviceScope.launch {
                            val revision = BleDiscoveryProtocol.networkRevision(hotspotManager?.getHotspotCredentials())
                            withContext(Dispatchers.Main) {
                                val advertiser = BleAdvertiser(this@BleHotspotService, deviceId, revision).also { bleAdvertiser = it }
                                advertiser.startAdvertising { advertiseResult ->
                                    bleStartInFlight = false
                                    advertiseResult.onSuccess {
                                        bleRetryAttempt = 0
                                        _serverState.value = ServerState.ADVERTISING
                                        advertiser.setHotspotActive(false, revision)
                                        gattServer?.updateServerStatus(ServerStatusMessage(ServerStatusMessage.Type.AVAILABLE, activeClients.size))
                                        startWakeRequestReceiver()
                                        updateServiceNotification()
                                    }.onFailure { error ->
                                        LogUtils.e(TAG, "BLE advertising failed", error)
                                        _serverState.value = ServerState.DEGRADED
                                        stopBleTransport()
                                        updateServiceNotification()
                                        scheduleBleRetry(deviceId)
                                    }
                                }
                            }
                        }
                    }.onFailure { error ->
                        bleStartInFlight = false
                        LogUtils.e(TAG, "GATT server startup failed", error)
                        _serverState.value = ServerState.DEGRADED
                        stopBleTransport()
                        updateServiceNotification()
                        scheduleBleRetry(deviceId)
                    }
                }
            }
        } catch (e: Exception) {
            bleStartInFlight = false
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
            bleRetryJob = null
            if (desiredRunning() && !pausedForAudio) withContext(Dispatchers.Main) { attemptStartBleServer(deviceId) }
        }
    }

    private fun stopBleServer() {
        cancelBleRetry()
        stopBleTransport()
        hotspotShutdownJob?.cancel(); hotspotShutdownJob = null
        heartbeatMonitorJob?.cancel(); heartbeatMonitorJob = null
        distressScanner?.stop(); distressScanner = null
        wakeScanner?.stop(); wakeScanner = null
        wakeScannerStartJob?.cancel(); wakeScannerStartJob = null
        wakeActivationJob?.cancel(); wakeActivationJob = null
        wakeLeaseJob?.cancel(); wakeLeaseJob = null
        udpControlServer.stop()
        activeClients.clear()
        _connectedClients.value = emptyList()
        _serverState.value = ServerState.STOPPED
        dismissApprovalNotification()
    }

    private fun stopBleTransport() {
        bleStartInFlight = false
        bleAdvertiser?.stopAdvertising()
        gattServer?.stopServer()
        bleAdvertiser = null
        gattServer = null
        distressScanner?.stop(); distressScanner = null
        wakeScanner?.stop(); wakeScanner = null
    }

    private fun cancelBleRetry() {
        bleRetryJob?.cancel(); bleRetryJob = null; bleRetryAttempt = 0
    }

    private fun checkAndRequestApproval(clientAddress: String, clientStableId: String?) {
        serviceScope.launch {
            if (hotspotManager?.isShizukuAvailable() != true) {
                gattServer?.markActivationFailed(clientAddress)
                _serverState.value = ServerState.NEEDS_SHIZUKU
                updateServiceNotification()
                return@launch
            }
            val dao = database.rememberedServerDao()
            val rememberedClient = if (!clientStableId.isNullOrBlank()) dao.getServerById(clientStableId) else dao.getServerByAddress(clientAddress)
            val defaultPolicy = AppPreferences.getDefaultApprovalPolicy(this@BleHotspotService)
            val resolvedClientId = resolveClientId(clientStableId, clientAddress)
            val pairingCode = gattServer?.getPairingCode(clientAddress)
            val resolvedClientName = pairingCode?.let { "Pairing code $it" } ?: resolveClientName(clientStableId, resolvedClientId)
            if (rememberedClient != null) dao.insertServer(rememberedClient.copy(deviceAddress = clientAddress, lastSeen = System.currentTimeMillis()))
            when (decideApprovalDecision(rememberedClient, defaultPolicy)) {
                ApprovalDecision.REQUEST_APPROVAL -> dispatchApprovalRequest(
                    clientAddress,
                    rememberedClient?.deviceId ?: resolvedClientId,
                    rememberedClient?.deviceName ?: resolvedClientName,
                    rememberedClient != null,
                    rememberedClient?.nickname
                )
                ApprovalDecision.AUTO_DENY -> {
                    if (rememberedClient == null) rememberAutoDecisionForNewClient(dao, clientAddress, resolvedClientId, resolvedClientName, AppPreferences.ApprovalPolicy.DENY)
                    denyClient(clientAddress)
                }
                ApprovalDecision.AUTO_APPROVE -> {
                    val approvedDeviceId = rememberedClient?.deviceId ?: resolvedClientId.also {
                        rememberAutoDecisionForNewClient(dao, clientAddress, resolvedClientId, resolvedClientName, AppPreferences.ApprovalPolicy.APPROVE)
                    }
                    activateHotspotAndSendCredentials(clientAddress, approvedDeviceId)
                }
            }
        }
    }

    internal fun decideApprovalDecision(rememberedClient: RememberedServer?, defaultPolicy: AppPreferences.ApprovalPolicy = AppPreferences.ApprovalPolicy.ASK): ApprovalDecision {
        if (rememberedClient == null) return when (defaultPolicy) {
            AppPreferences.ApprovalPolicy.ASK -> ApprovalDecision.REQUEST_APPROVAL
            AppPreferences.ApprovalPolicy.APPROVE -> ApprovalDecision.AUTO_APPROVE
            AppPreferences.ApprovalPolicy.DENY -> ApprovalDecision.AUTO_DENY
        }
        return when (rememberedClient.approvalPolicy) {
            RememberedServer.APPROVAL_POLICY_DENIED -> ApprovalDecision.AUTO_DENY
            RememberedServer.APPROVAL_POLICY_ASK -> ApprovalDecision.REQUEST_APPROVAL
            RememberedServer.APPROVAL_POLICY_APPROVED -> if (rememberedClient.isApproved) ApprovalDecision.AUTO_APPROVE else ApprovalDecision.REQUEST_APPROVAL
            else -> ApprovalDecision.REQUEST_APPROVAL
        }
    }

    internal fun mapDefaultPolicyToRememberedPolicy(policy: AppPreferences.ApprovalPolicy): String = when (policy) {
        AppPreferences.ApprovalPolicy.ASK -> RememberedServer.APPROVAL_POLICY_ASK
        AppPreferences.ApprovalPolicy.APPROVE -> RememberedServer.APPROVAL_POLICY_APPROVED
        AppPreferences.ApprovalPolicy.DENY -> RememberedServer.APPROVAL_POLICY_DENIED
    }

    internal fun mergeApprovalMetadata(existing: RememberedServer, clientAddress: String, clientName: String, approvedAt: Long): RememberedServer {
        val fallbackName = "Client-${existing.deviceId}"
        val incomingName = clientName.takeUnless { it.isBlank() || it == "Unknown Device" }
        return existing.copy(
            deviceName = existing.deviceName.ifBlank { incomingName ?: fallbackName },
            deviceAddress = clientAddress,
            lastSeen = approvedAt,
            lastApprovedAt = approvedAt,
            isApproved = true
        )
    }

    private fun resolveClientId(clientStableId: String?, clientAddress: String): String =
        clientStableId?.takeIf { it.isNotBlank() } ?: "addr-${clientAddress.filter { it.isLetterOrDigit() }.lowercase()}"

    private fun resolveClientName(clientStableId: String?, resolvedClientId: String): String =
        clientStableId?.takeIf { it.isNotBlank() } ?: "Client-$resolvedClientId"

    private suspend fun rememberAutoDecisionForNewClient(
        dao: com.agentkosticka.easierspot.data.db.RememberedServerDao,
        clientAddress: String,
        clientDeviceId: String,
        clientName: String,
        policy: AppPreferences.ApprovalPolicy
    ) {
        val now = System.currentTimeMillis()
        val isApproved = policy == AppPreferences.ApprovalPolicy.APPROVE
        dao.insertServer(RememberedServer(
            deviceId = clientDeviceId,
            deviceName = clientName,
            deviceAddress = clientAddress,
            lastSeen = now,
            isApproved = isApproved,
            nickname = null,
            approvalPolicy = mapDefaultPolicyToRememberedPolicy(policy),
            lastApprovedAt = if (isApproved) now else 0L
        ))
    }

    private fun dispatchApprovalRequest(clientAddress: String, deviceId: String, deviceName: String?, isRememberedClient: Boolean, nickname: String?) {
        val normalizedDisplayId = normalizeIdentityForDisplay(deviceId)
        val normalizedDisplayName = normalizeIdentityForDisplay(deviceName).takeUnless { it == "Unknown" } ?: normalizedDisplayId
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
        showApprovalNotification(clientAddress, deviceId, deviceName, isRememberedClient, normalizedDisplayId, normalizedDisplayName, nickname)
    }

    private suspend fun activateHotspotAndSendCredentials(clientAddress: String, clientDeviceId: String? = null) {
        _serverState.value = ServerState.HOTSPOT_STARTING
        withContext(Dispatchers.Main) {
            gattServer?.markHotspotStarting(clientAddress)
            bleAdvertiser?.setHotspotStarting(true)
        }
        updateServiceNotification()
        val wasAlreadyActive = hotspotManager?.isHotspotEnabled() == true || hotspotManager?.isHotspotStarting() == true
        val result = ensureCoalescedHotspotReady()
        withContext(Dispatchers.Main) { bleAdvertiser?.setHotspotStarting(false) }
        if (result !is HotspotActivationState.Ready) {
            withContext(Dispatchers.Main) { gattServer?.markActivationFailed(clientAddress) }
            _serverState.value = ServerState.ADVERTISING
            updateServiceNotification()
            return
        }
        if (!wasAlreadyActive) setHotspotOwned(true, hotspotManager?.getHotspotCredentials())
        sendCredentialsToClient(clientAddress, clientDeviceId)
    }

    private suspend fun sendCredentialsToClient(clientAddress: String, clientDeviceId: String? = null) {
        val credentials = hotspotManager?.getHotspotCredentials()
        if (credentials != null && credentials.ssid.isNotEmpty()) {
            if (hotspotStartedByApp) setHotspotOwned(true, credentials)
            updateLastApprovedAt(clientAddress, clientDeviceId)
            withContext(Dispatchers.Main) {
                gattServer?.approveClient(clientAddress)
                delay(100)
                gattServer?.sendHotspotCredentials(clientAddress, credentials)
            }
            val stableId = clientDeviceId ?: gattServer?.stableIdForAddress(clientAddress)
            val clientPublicKey = gattServer?.clientPublicKeyForAddress(clientAddress)
            if (stableId != null && clientPublicKey != null) wakePeerStore.remember(stableId, clientPublicKey)
            _serverState.value = ServerState.SHARING
            stableId?.let {
                activeClients[it] = ActiveClient(System.currentTimeMillis())
                publishConnectedClients()
                startClientLivenessTracking()
            }
            bleAdvertiser?.setHotspotActive(true, BleDiscoveryProtocol.networkRevision(credentials))
            wakeScanner?.stop(); wakeScanner = null
            udpControlServer.start(serviceScope)
            updateServiceNotification()
        } else {
            withContext(Dispatchers.Main) { gattServer?.denyClient(clientAddress) }
        }
    }

    private fun approveClient(clientAddress: String, clientDeviceId: String, clientName: String) {
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val existing = dao.getServerById(clientDeviceId)
            val approvedAt = System.currentTimeMillis()
            if (existing != null) dao.insertServer(mergeApprovalMetadata(existing, clientAddress, clientName, approvedAt))
            else dao.insertServer(RememberedServer(deviceId = clientDeviceId, deviceName = clientName.ifBlank { "Client-$clientDeviceId" }, deviceAddress = clientAddress, lastSeen = approvedAt, lastApprovedAt = approvedAt, isApproved = true))
            activateHotspotAndSendCredentials(clientAddress, clientDeviceId)
        }
    }

    private suspend fun updateLastApprovedAt(clientAddress: String, clientDeviceId: String?) {
        val dao = database.rememberedServerDao()
        val server = if (!clientDeviceId.isNullOrBlank()) dao.getServerById(clientDeviceId) else dao.getServerByAddress(clientAddress)
        server?.let {
            val approvedAt = System.currentTimeMillis()
            dao.insertServer(it.copy(deviceAddress = clientAddress, lastSeen = approvedAt, lastApprovedAt = approvedAt, isApproved = true))
        }
    }

    private fun denyClient(clientAddress: String) { gattServer?.denyClient(clientAddress) }

    private fun showApprovalNotification(
        clientAddress: String,
        deviceId: String,
        deviceName: String?,
        isRememberedClient: Boolean = false,
        displayId: String? = null,
        displayName: String? = null,
        nickname: String? = null
    ) {
        if (!canPostNotifications()) return
        val normalizedDisplayId = displayId ?: normalizeIdentityForDisplay(deviceId)
        val normalizedDisplayName = displayName ?: normalizeIdentityForDisplay(deviceName).takeUnless { it == "Unknown" } ?: normalizedDisplayId
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
        val pendingIntent = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun decisionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BleHotspotService::class.java).apply {
                this.action = action
                putExtra(EXTRA_CLIENT_ADDRESS, clientAddress)
                putExtra(EXTRA_CLIENT_DEVICE_ID, deviceId)
                putExtra(EXTRA_CLIENT_NAME, normalizedDisplayName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val friendlyName = nickname?.takeIf { it.isNotBlank() } ?: normalizedDisplayName
        val pairingDetail = deviceName?.takeIf { it.contains("code", ignoreCase = true) } ?: "Verify the pairing code shown on the other phone"
        val notificationBuilder = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("Share Wi-Fi with $friendlyName?")
            .setContentText(pairingDetail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$pairingDetail\nDevice: $normalizedDisplayId"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(0, "Deny", decisionIntent(ACTION_DENY_CLIENT, 31))
            .addAction(0, "Approve", decisionIntent(ACTION_APPROVE_CLIENT, 32))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        var defaults = 0
        if (AppPreferences.isNotificationSoundEnabled(this)) defaults = defaults or NotificationCompat.DEFAULT_SOUND
        if (AppPreferences.isNotificationVibrationEnabled(this)) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
        if (defaults != 0) notificationBuilder.setDefaults(defaults)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(APPROVAL_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun dismissApprovalNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(APPROVAL_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        runCatching { Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(shizukuBinderDeadListener) }
        stopBleServer()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun persistServerState(running: Boolean, deviceId: String? = currentDeviceId) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_RUNNING, running)
            if (deviceId != null) putString(KEY_DEVICE_ID, deviceId)
        }
    }

    private fun desiredRunning(): Boolean = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    private fun stopServerAndSelf() {
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                gattServer?.updateServerStatus(ServerStatusMessage(ServerStatusMessage.Type.SERVER_STOPPING, activeClients.size))
            }
            delay(500L)
            withContext(Dispatchers.Main) { stopBleServer() }
            stopOwnedHotspot()
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@BleHotspotService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun stopOwnedHotspot() {
        if (!hotspotStartedByApp) return
        val accepted = hotspotManager?.stopHotspot() == true
        if (!accepted) return
        val manager = hotspotManager ?: return
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        while (manager.isHotspotEnabled() && android.os.SystemClock.elapsedRealtime() < deadline) delay(250L)
        if (manager.isHotspotEnabled()) return
        setHotspotOwned(false)
        bleAdvertiser?.setHotspotActive(false)
        udpControlServer.stop()
        activeClients.clear()
        distressScanner?.stop(); distressScanner = null
        hotspotShutdownJob?.cancel(); hotspotShutdownJob = null
        if (desiredRunning()) startWakeRequestReceiver()
    }

    private fun startClientLivenessTracking() {
        if (distressScanner == null) {
            distressScanner = BleDistressScanner(this) { payload ->
                gattServer?.verifyDistress(payload)?.let { removeActiveClient(it, "authenticated BLE distress") }
            }.also(BleDistressScanner::start)
        }
        if (heartbeatMonitorJob?.isActive == true) return
        heartbeatMonitorJob = serviceScope.launch {
            while (true) {
                delay(BleConstants.HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val expiry = BleConstants.UDP_CLIENT_EXPIRY_MS + 10_000L
                activeClients.entries.filter { now - it.value.lastHeartbeatAt >= expiry }
                    .forEach { removeActiveClient(it.key, "missed ${BleConstants.MAX_MISSED_HEARTBEATS} heartbeats") }
                if (hotspotStartedByApp && hotspotManager?.isHotspotEnabled() != true) {
                    setHotspotOwned(false)
                    activeClients.clear()
                } else if (shouldAutoStopHotspot(hotspotStartedByApp, activeClients.size) && hotspotShutdownJob?.isActive != true) {
                    scheduleNoClientShutdown()
                }
            }
        }
    }

    private fun startWakeRequestReceiver() {
        if (wakeScanner != null || wakeScannerStartJob?.isActive == true) return
        wakeScannerStartJob = serviceScope.launch {
            val routes = wakePeerStore.wakeRoutes()
            withContext(Dispatchers.Main) {
                if (wakeScanner != null || routes.isEmpty() || !desiredRunning()) return@withContext
                wakeScanner = BleWakeScanner(this@BleHotspotService, { routes }) { payload ->
                    serviceScope.launch {
                        val peer = wakePeerStore.verifyAndAdvance(payload) ?: return@launch
                        if (!wakeBoostLimiter.allow(peer)) return@launch
                        activateHotspotFromWake(peer)
                    }
                }.also(BleWakeScanner::start)
                wakeScannerStartJob = null
            }
        }
    }

    private fun activateHotspotFromWake(peer: String) {
        if (wakeActivationJob?.isActive == true) { renewWakeLease(); return }
        renewWakeLease()
        wakeActivationJob = serviceScope.launch {
            _serverState.value = ServerState.HOTSPOT_STARTING
            withContext(Dispatchers.Main) { bleAdvertiser?.setHotspotStarting(true) }
            updateServiceNotification()
            val wasAlreadyActive = hotspotManager?.isHotspotEnabled() == true || hotspotManager?.isHotspotStarting() == true
            val result = ensureCoalescedHotspotReady()
            withContext(Dispatchers.Main) { bleAdvertiser?.setHotspotStarting(false) }
            if (result is HotspotActivationState.Ready) {
                val credentials = hotspotManager?.getHotspotCredentials()
                if (!wasAlreadyActive) setHotspotOwned(true, credentials)
                _serverState.value = ServerState.SHARING
                bleAdvertiser?.setHotspotActive(true, BleDiscoveryProtocol.networkRevision(credentials))
                wakeScanner?.stop(); wakeScanner = null
                udpControlServer.start(serviceScope)
                renewWakeLease()
                updateServiceNotification()
                LogUtils.i(TAG, "Authenticated wake from $peer started the hotspot")
            } else {
                _serverState.value = ServerState.ADVERTISING
                updateServiceNotification()
            }
        }.also { job -> job.invokeOnCompletion { wakeActivationJob = null } }
    }

    private fun renewWakeLease() {
        wakeLeaseJob?.cancel()
        wakeLeaseJob = serviceScope.launch {
            delay(BleConstants.HOTSPOT_WAKE_LEASE_MS)
            if (activeClients.isEmpty() && hotspotStartedByApp) {
                stopOwnedHotspot()
                if (desiredRunning()) {
                    _serverState.value = ServerState.ADVERTISING
                    startWakeRequestReceiver()
                    updateServiceNotification()
                }
            }
        }
    }

    private suspend fun ensureCoalescedHotspotReady(): HotspotActivationState = hotspotActivationMutex.withLock {
        hotspotManager?.ensureHotspotReady(35_000L) ?: HotspotActivationState.Failed("Hotspot controller is unavailable")
    }

    private fun onUdpControl(fingerprint: String, type: Byte) {
        when (type) {
            BleConstants.UDP_HELLO, BleConstants.UDP_HEARTBEAT -> {
                activeClients[fingerprint] = ActiveClient(System.currentTimeMillis())
                publishConnectedClients()
                wakeLeaseJob?.cancel()
                hotspotShutdownJob?.cancel()
                startClientLivenessTracking()
            }
            BleConstants.UDP_GOODBYE -> removeActiveClient(fingerprint, "authenticated UDP goodbye")
        }
    }

    private fun removeActiveClient(stableId: String, reason: String) {
        if (activeClients.remove(stableId) == null) return
        LogUtils.i(TAG, "Client $stableId inactive: $reason")
        publishConnectedClients()
        if (activeClients.isEmpty()) scheduleNoClientShutdown()
    }

    private fun disconnectActiveClient(stableId: String) {
        if (!activeClients.containsKey(stableId)) return
        val server = gattServer ?: run {
            removeActiveClient(stableId, "disconnected by sharing phone")
            return
        }
        val address = server.stableAddress(stableId)
        if (address != null) {
            server.sendServerStatus(address, ServerStatusMessage(ServerStatusMessage.Type.CLIENT_DISCONNECTED, (activeClients.size - 1).coerceAtLeast(0)))
        }
        serviceScope.launch {
            delay(350L)
            withContext(Dispatchers.Main) { server.disconnectAuthenticatedClient(stableId) }
            removeActiveClient(stableId, "disconnected by sharing phone")
        }
    }

    private fun publishConnectedClients() {
        val snapshot = activeClients.mapValues { it.value.lastHeartbeatAt }
        val statusType = if (snapshot.isEmpty()) ServerStatusMessage.Type.AVAILABLE else ServerStatusMessage.Type.SHARING
        gattServer?.updateServerStatus(ServerStatusMessage(statusType, snapshot.size))
        serviceScope.launch {
            val dao = database.rememberedServerDao()
            val summaries = snapshot.map { (stableId, seenAt) ->
                val remembered = dao.getServerById(stableId)
                ConnectedClientSummary(
                    stableId,
                    remembered?.nickname?.takeIf { it.isNotBlank() }
                        ?: remembered?.deviceName?.takeIf { it.isNotBlank() && !it.equals("Unknown Device", ignoreCase = true) }
                        ?: normalizeIdentityForDisplay(stableId),
                    seenAt
                )
            }.sortedBy { it.label.lowercase() }
            _connectedClients.value = summaries
            updateServiceNotification()
        }
    }

    private fun scheduleNoClientShutdown() {
        if (!shouldAutoStopHotspot(hotspotStartedByApp, activeClients.size)) return
        hotspotShutdownJob?.cancel()
        hotspotShutdownJob = serviceScope.launch {
            delay(10_000L)
            if (shouldAutoStopHotspot(hotspotStartedByApp, activeClients.size)) {
                stopOwnedHotspot()
                if (desiredRunning()) {
                    _serverState.value = ServerState.ADVERTISING
                    updateServiceNotification()
                }
            }
        }
    }

    private fun pauseForAudioConnection() {
        if (!desiredRunning() || pausedForAudio) return
        pausedForAudio = true
        cancelBleRetry()
        if (activeClients.isEmpty()) stopBleTransport() else {
            bleAdvertiser?.stopAdvertising(); bleAdvertiser = null
            wakeScanner?.stop(); wakeScanner = null
        }
        _serverState.value = ServerState.PAUSED_FOR_AUDIO
        updateServiceNotification()
    }

    private fun resumeAfterAudioConnection() {
        if (!pausedForAudio) return
        pausedForAudio = false
        val deviceId = currentDeviceId ?: return
        if (gattServer != null && activeClients.isNotEmpty()) {
            val revision = BleDiscoveryProtocol.networkRevision(hotspotManager?.getHotspotCredentials())
            BleAdvertiser(this, deviceId, revision).also { advertiser ->
                bleAdvertiser = advertiser
                advertiser.setHotspotActive(true)
                advertiser.startAdvertising { result ->
                    _serverState.value = if (result.isSuccess) ServerState.SHARING else ServerState.DEGRADED
                    if (result.isSuccess) startWakeRequestReceiver()
                    updateServiceNotification()
                }
            }
        } else {
            _serverState.value = ServerState.STARTING
            startBleServer(deviceId)
        }
    }

    private fun setHotspotOwned(owned: Boolean, credentials: com.agentkosticka.easierspot.data.model.HotspotCredentials? = null) {
        hotspotStartedByApp = owned
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit {
            putBoolean(KEY_HOTSPOT_OWNED, owned)
            if (owned && credentials != null) {
                putInt(KEY_HOTSPOT_BOOT_COUNT, currentBootCount())
                putString(KEY_HOTSPOT_SSID, credentials.ssid)
                putInt(KEY_HOTSPOT_REVISION, BleDiscoveryProtocol.networkRevision(credentials))
                putLong(KEY_HOTSPOT_STARTED_AT, System.currentTimeMillis())
            } else if (!owned) {
                remove(KEY_HOTSPOT_BOOT_COUNT); remove(KEY_HOTSPOT_SSID); remove(KEY_HOTSPOT_REVISION); remove(KEY_HOTSPOT_STARTED_AT)
            }
        }
    }

    private fun currentBootCount(): Int = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private fun updateServiceNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(SERVICE_CHANNEL_ID, "BLE Hotspot Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Running BLE hotspot sharing"
        }
        val alertsChannel = NotificationChannel(ALERTS_CHANNEL_ID, "Hotspot approvals and prompts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Approval and hotspot enable prompts"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannels(listOf(serviceChannel, alertsChannel))
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, ServerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPendingIntent = PendingIntent.getService(this, 3, Intent(this, BleHotspotService::class.java).apply { action = ACTION_STOP_SERVER }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pausePendingIntent = PendingIntent.getService(this, 4, Intent(this, BleHotspotService::class.java).apply { action = if (pausedForAudio) ACTION_RESUME_SERVER else ACTION_PAUSE_SERVER }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val restorePendingIntent = PendingIntent.getService(this, 5, Intent(this, BleHotspotService::class.java).apply { action = ACTION_RESTORE_NOTIFICATION }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val statusText = when (_serverState.value) {
            ServerState.STARTING -> "Starting Bluetooth sharing…"
            ServerState.NEEDS_SHIZUKU -> "Waiting for Shizuku…"
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
            .setDeleteIntent(restorePendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (pausedForAudio) "Resume" else "Pause 30s", pausePendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build().apply { flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR }
    }

    private fun canPostNotifications(): Boolean {
        val hasRuntimePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
        return hasRuntimePermission && NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
}
